# SPEC-ingestion.md — Muralis

> This spec defines the `ingestion/` package in full. It covers the
> Binance WebSocket connection, the snapshot bootstrap sequence, delta
> application, gap detection, reconnection logic, and JSON parsing.
>
> **Claude Code instruction:** All classes generated from this spec live
> in `com.muralis.ingestion`. They may import from `com.muralis.model`
> and `com.muralis.provider` only. No imports from `com.muralis.engine`
> or `com.muralis.ui` are permitted under any circumstance. See
> `ARCHITECTURE.md` Section 4.

---

## 1. Scope

This spec covers exactly the following classes:

| Class | Responsibility |
|---|---|
| `BinanceAdapter` | Implements `MarketDataProvider`. Owns the full connection lifecycle. |
| `BinanceWebSocketClient` | Extends `WebSocketClient`. Receives raw frames, delegates to parser. |
| `BinanceMessageParser` | Parses raw Binance JSON strings into `MarketEvent` instances. |
| `SnapshotFetcher` | Fetches the REST order book snapshot via `HttpClient`. |

No other classes belong in `com.muralis.ingestion`. If a helper is
needed, it is a private static method or a private inner record — not a
new top-level class.

---

## 2. Binance WebSocket streams

### 2.1 Connection URL
```
wss://stream.binance.com:9443/stream?streams=<symbol>@depth@100ms/<symbol>@trade
```

For BTCUSDT:
```
wss://stream.binance.com:9443/stream?streams=btcusdt@depth@100ms/btcusdt@trade
```

**Rules:**
- Symbol in the URL is **lowercase** (Binance requirement)
- Both streams are combined into a single WebSocket connection
- The combined stream wraps each message in an envelope:
  ```json
  { "stream": "btcusdt@depth@100ms", "data": { ... } }
  ```
- The `stream` field is used to route the `data` payload to the correct
  parser method — depth parser or trade parser

### 2.2 Depth stream (`@depth@100ms`)
Update frequency: every 100ms. Each message is a diff (delta) update.

**Raw message shape:**
```json
{
  "stream": "btcusdt@depth@100ms",
  "data": {
    "e": "depthUpdate",
    "E": 1234567891234,
    "s": "BTCUSDT",
    "U": 10041,
    "u": 10045,
    "b": [["97432.51", "1.23400000"], ["97431.00", "0.00000000"]],
    "a": [["97433.00", "0.50000000"]]
  }
}
```

**Field mapping to `OrderBookDelta`:**

| JSON field | Meaning | Maps to |
|---|---|---|
| `E` | Event time (ms) | `exchangeTs` |
| `s` | Symbol (uppercase) | `symbol` |
| `U` | First update ID in batch | `firstUpdateId` |
| `u` | Last update ID in batch | `finalUpdateId` |
| `b` | Bid changes `[price, qty][]` | `bidPrices[]`, `bidQtys[]` |
| `a` | Ask changes `[price, qty][]` | `askPrices[]`, `askQtys[]` |

**Critical parsing rule:** A quantity of `"0.00000000"` means **remove
that price level**. It must be stored as `0L` in the delta. The engine
interprets `qty == 0L` as a deletion instruction. This is not a
zero-volume level — it is an absence instruction.

### 2.3 Trade stream (`@trade`)
Delivered in real time for every matched trade.

**Raw message shape:**
```json
{
  "stream": "btcusdt@trade",
  "data": {
    "e": "trade",
    "E": 1234567891234,
    "s": "BTCUSDT",
    "t": 12345,
    "p": "97432.51",
    "q": "0.00041800",
    "T": 1234567891123,
    "m": true
  }
}
```

**Field mapping to `NormalizedTrade`:**

| JSON field | Meaning | Maps to |
|---|---|---|
| `T` | Trade time (ms) | `exchangeTs` — use `T`, not `E` |
| `s` | Symbol | `symbol` |
| `t` | Trade ID | `tradeId` |
| `p` | Price (string) | `price` (via `parsePrice()`) |
| `q` | Quantity (string) | `qty` (via `parseQty()`) |
| `m` | isBuyerMaker | `aggressorSide` (via derivation rule) |

**Critical field note:** Use `T` (trade time) for `exchangeTs`, not `E`
(event time). `T` is when the trade matched on the exchange. `E` is when
the event was published to the stream — always slightly later. For candle
and bubble timing, `T` is the correct timestamp.

**`AggressorSide` derivation (defined once here, never elsewhere):**
```
m == false  →  AggressorSide.BUY   (buyer lifted the offer)
m == true   →  AggressorSide.SELL  (seller hit the bid)
```

---

## 3. Snapshot bootstrap sequence

This is the most critical procedure in the ingestion layer. An incorrect
bootstrap produces a corrupted order book that will never self-correct.
The sequence must be followed exactly.

### 3.1 Full bootstrap sequence

```
Step 1. Open WebSocket connection to combined stream URL
        Begin buffering ALL incoming depth events in a local
        ConcurrentLinkedQueue<OrderBookDelta> (the "pre-buffer")
        Do NOT publish any delta to the main queue yet.
        Trade events MAY be published to the main queue immediately —
        they are not affected by snapshot sequencing.

Step 2. Publish ConnectionEvent(CONNECTING) to the main queue.

Step 3. Fetch REST snapshot:
        GET https://api.binance.com/api/v3/depth?symbol=BTCUSDT&limit=5000
        Parse response into OrderBookSnapshot.
        Record snapshot.lastUpdateId.

Step 4. Validate the pre-buffer against the snapshot:
        Discard any buffered delta where delta.finalUpdateId <= snapshot.lastUpdateId
        These deltas describe state already included in the snapshot.

Step 5. Find the first valid delta in the pre-buffer:
        A delta is valid as the first delta if and only if:
            delta.firstUpdateId <= snapshot.lastUpdateId + 1
            AND
            delta.finalUpdateId >= snapshot.lastUpdateId + 1

        If no valid first delta exists in the pre-buffer:
            → The snapshot is stale. Discard it and the pre-buffer.
            → Wait 250ms.
            → Return to Step 3 (re-fetch snapshot).
            → Do not increment the reconnect counter — this is
              normal bootstrap behaviour, not a failure.

Step 6. Publish OrderBookSnapshot to the main queue.

Step 7. Publish all valid buffered deltas to the main queue in order,
        starting from the first valid delta identified in Step 5.

Step 8. Switch to live mode: stop buffering. Publish all subsequent
        depth deltas directly to the main queue as they arrive.

Step 9. Publish ConnectionEvent(CONNECTED) to the main queue.
```

### 3.2 Sequence diagram
```
WebSocket         pre-buffer          REST             main queue
    │                  │               │                    │
    │──depth delta─────▶ buffer        │                    │
    │──depth delta─────▶ buffer        │                    │
    │──trade───────────────────────────────────────────────▶│ (trades bypass buffer)
    │                  │               │                    │
    │                  │   GET /depth──▶                    │
    │                  │               │──snapshot──────────▶│ (Step 6)
    │                  │               │                    │
    │──depth delta─────▶ buffer        │                    │
    │                  │               │                    │
    │                  │  validate + drain buffer ──────────▶│ (Step 7)
    │                  │               │                    │
    │──depth delta─────────────────────────────────────────▶│ (live mode, Step 8)
```

### 3.3 Snapshot REST endpoint details
```
URL:     https://api.binance.com/api/v3/depth
Params:  symbol=BTCUSDT&limit=5000
Method:  GET
Client:  java.net.http.HttpClient (JDK 11+, zero extra dependency)
Timeout: 10 seconds connect + 10 seconds read
```

**Response shape:**
```json
{
  "lastUpdateId": 10040,
  "bids": [["97432.51", "1.23400000"], ...],
  "asks": [["97433.00", "0.50000000"], ...]
}
```

`lastUpdateId` is the sequence ID corresponding to this snapshot.
`bids` are sorted descending by price. `asks` are sorted ascending.
Both must be parsed into fixed-point `long[]` arrays using the canonical
`parsePrice()` and `parseQty()` patterns from `DATA-CONTRACTS.md` Section 7.

---

## 4. Gap detection

Once in live mode (Step 8 above), every incoming delta must be checked
for sequence continuity.

### 4.1 Gap detection rule

Gap detection is performed by `BinanceAdapter` on the ingestion thread,
**before** publishing the delta to the main queue. The adapter tracks
the last published delta's `finalUpdateId` in a field called
`lastPublishedFinalUpdateId`. This naming clarifies that it is the
adapter (not the engine) performing sequence validation.

```
For each incoming delta after the first:
    expected = lastPublishedFinalUpdateId + 1

    if delta.firstUpdateId != expected:
        → GAP DETECTED
        → Log: WARN "Gap detected on {symbol}: expected firstUpdateId={expected},
                got {delta.firstUpdateId}. Triggering reconnect."
        → Publish ConnectionEvent(RECONNECTING, reason="Gap detected: ...")
        → Discard the corrupted delta (do not apply it)
        → Trigger reconnection sequence (Section 5)
        → Return — do not process further events until reconnected

    else:
        → Publish delta to main queue
        → lastPublishedFinalUpdateId = delta.finalUpdateId
```

### 4.2 What constitutes a gap
A gap is any delta where `firstUpdateId` does not equal
`lastPublishedFinalUpdateId + 1`. This includes:

- Missing updates (network packet loss)
- Duplicate updates (`firstUpdateId < expected`) — log and discard
  without triggering reconnect, as duplicates are harmless
- Out-of-order updates (`firstUpdateId > expected`) — always reconnect

**Duplicate handling:**
```
if delta.finalUpdateId <= lastPublishedFinalUpdateId:
    → Log: DEBUG "Duplicate delta received on {symbol}, discarding."
    → Discard silently. Do not reconnect.
```

---

## 5. Reconnection logic

### 5.1 Reconnection trigger conditions
Reconnection is triggered by any of the following:

| Trigger | Source |
|---|---|
| WebSocket `onClose` callback | Java-WebSocket library |
| WebSocket `onError` callback | Java-WebSocket library |
| Gap detected (Section 4) | `BinanceAdapter` internal |
| Snapshot stale after 3 re-fetch attempts | `BinanceAdapter` internal |

### 5.2 Reconnection backoff schedule

```
Attempt 1:  immediate (0ms wait)
Attempt 2:  immediate (0ms wait)
Attempt 3:  immediate (0ms wait)
Attempt 4:  500ms wait
Attempt 5:  1000ms wait
Attempt 6:  2000ms wait
Attempt 7:  4000ms wait
Attempt 8+: 30000ms wait (cap — retry every 30 seconds indefinitely)
```

**Implementation note:** The attempt counter resets to 1 on every
successful `ConnectionEvent(CONNECTED)`. It does not reset on
`RECONNECTING` — only on a fully established connection.

### 5.3 Reconnection procedure
```
Step 1. Close the existing WebSocket connection if still open.
        Call webSocketClient.closeBlocking() with a 3-second timeout.

Step 2. Discard the pre-buffer entirely.
        Set lastPublishedFinalUpdateId = -1.
        Do NOT discard the main queue — the engine will handle the
        RECONNECTING event and reset its own state.

Step 3. Wait for the backoff interval (see Section 5.2).

Step 4. Log: INFO "Reconnecting to Binance ({symbol}), attempt {n}..."

Step 5. Create a new BinanceWebSocketClient instance.
        (Java-WebSocket clients are not reusable after close.)

Step 6. Execute the full bootstrap sequence (Section 3) from Step 1.
```

### 5.4 Trades during reconnection
Trades received between `ConnectionEvent(RECONNECTING)` and the next
`ConnectionEvent(CONNECTED)` are **discarded**. They are not buffered
and not replayed.

**UI responsibility:** The UI must display an amber/yellow status
indicator whenever `ConnectionState == RECONNECTING`. This is the
trader's signal that the bubble stream may have gaps. See `SPEC-rendering.md`.

**Phase 2 upgrade path:** A future `TradeBackfiller` class may fetch
missed trades from `GET /api/v3/aggTrades?startTime=...&endTime=...`
after reconnection and inject them as `NormalizedTrade` events before
the first live delta. This is not implemented in Phase 1. The
`BinanceAdapter` must not be structured in a way that prevents this
from being added later — specifically, the reconnect timestamp must be
recorded even though it is unused in Phase 1:
```java
private long disconnectTs = -1L; // Record on RECONNECTING, use in Phase 2
```

---

## 6. `BinanceAdapter` — class specification

```
package com.muralis.ingestion

implements com.muralis.provider.MarketDataProvider

Constructor:
    BinanceAdapter(
        LinkedTransferQueue<MarketEvent> queue,
        InstrumentSpec                   instrumentSpec
    )

Fields (all private):
    queue            LinkedTransferQueue<MarketEvent>
    instrumentSpec   InstrumentSpec
    listeners        List<MarketDataListener>   (for ConnectionState callbacks)
    wsClient         BinanceWebSocketClient     (replaced on each reconnect)
    preBuffer        ConcurrentLinkedQueue<OrderBookDelta>
    reconnectCount   int                        (reset on CONNECTED)
    disconnectTs     long                       (Phase 2 placeholder, record always)
    lastPublishedFinalUpdateId  long            (last published finalUpdateId; -1 = none)
    state            ConnectionState            (current state, volatile)

Public methods (from MarketDataProvider interface):
    void connect()        → starts bootstrap sequence on calling thread
    void disconnect()     → closes WebSocket, publishes DISCONNECTED, stops retrying
    void addListener(MarketDataListener l)

Private methods:
    void runBootstrap()               → Section 3 sequence
    void handleDepthMessage(String)   → parse + buffer or publish delta
    void handleTradeMessage(String)   → parse + publish trade
    boolean detectGap(OrderBookDelta) → Section 4 rule
    void triggerReconnect(String)     → Section 5 procedure
    void publishEvent(MarketEvent)    → wraps queue.offer() with timeout logging
    void publishConnectionEvent(ConnectionState, String reason)
```

### 6.1 `publishEvent` contract
```java
private void publishEvent(MarketEvent event) {
    boolean accepted = queue.offer(event);
    if (!accepted) {
        // LinkedTransferQueue is unbounded — offer() never returns false.
        // This branch exists as a defensive log point for future queue
        // replacement with a bounded queue.
        log.warn("Queue rejected event — type={}", event.getClass().getSimpleName());
    }
}
```

### 6.2 Thread safety rules for `BinanceAdapter`
- `runBootstrap()` and `triggerReconnect()` are always called from the
  ingestion thread (WebSocket callback thread). Never from the engine
  or UI thread.
- `disconnect()` may be called from any thread (typically the UI thread
  on application close). It must be safe to call from any thread.
- `state` is `volatile`. All reads and writes use the `volatile` field
  directly — no additional synchronisation is needed given single-writer
  (ingestion thread) except for `disconnect()`.
- `preBuffer` is `ConcurrentLinkedQueue` — safe for concurrent offer
  from ingestion thread and drain from bootstrap logic on same thread
  (single-threaded access in practice, but ConcurrentLinkedQueue is
  used defensively).

---

## 7. `BinanceWebSocketClient` — class specification

```
package com.muralis.ingestion

extends org.java_websocket.client.WebSocketClient

Constructor:
    BinanceWebSocketClient(URI serverUri, BinanceAdapter adapter)

Overridden callbacks:
    onOpen(ServerHandshake)   → log INFO "WebSocket connected"
    onMessage(String message) → delegate to adapter.handleMessage(message)
    onClose(int, String, boolean) → delegate to adapter.triggerReconnect(reason)
    onError(Exception)        → log ERROR, delegate to adapter.triggerReconnect(reason)
```

`BinanceWebSocketClient` contains zero business logic. It is a pure
delegation shim. All routing, parsing, and state management is in
`BinanceAdapter` and `BinanceMessageParser`.

**Message routing in `handleMessage`:**
```java
// Route based on the "stream" field in the envelope
JsonObject envelope = gson.fromJson(message, JsonObject.class);
String stream = envelope.get("stream").getAsString();
JsonObject data = envelope.getAsJsonObject("data");

if (stream.endsWith("@depth@100ms")) {
    adapter.handleDepthMessage(data);
} else if (stream.endsWith("@trade")) {
    adapter.handleTradeMessage(data);
} else {
    log.warn("Unknown stream type received: {}", stream);
}
```

---

## 8. `BinanceMessageParser` — class specification

```
package com.muralis.ingestion

Constructor:
    BinanceMessageParser(InstrumentSpec instrumentSpec)

Public methods:
    OrderBookDelta parseDelta(JsonObject data)
    NormalizedTrade parseTrade(JsonObject data)

Private methods:
    long parsePrice(String raw)   → uses instrumentSpec.priceScale
    long parseQty(String raw)     → uses instrumentSpec.qtyScale
    long[][] parseLevels(JsonArray levels)  → parses [[price,qty],...] arrays
```

### 8.1 Parsing rules
All parsing follows `DATA-CONTRACTS.md` Section 7 exactly.

```java
private long parsePrice(String raw) {
    return new BigDecimal(raw)
        .movePointRight(instrumentSpec.priceScale())
        .longValueExact();
}

private long parseQty(String raw) {
    return new BigDecimal(raw)
        .movePointRight(instrumentSpec.qtyScale())
        .longValueExact();
}
```

`longValueExact()` throws `ArithmeticException` if precision is lost.
This is intentional. Catch at the `BinanceAdapter` level, log as ERROR,
and trigger reconnect — a parse failure indicates a data contract
violation that cannot be recovered from silently.

### 8.2 `parseDelta` output contract
- `bidPrices` and `bidQtys` are parallel arrays of equal length
- `askPrices` and `askQtys` are parallel arrays of equal length
- Arrays may have length 0 if the delta contains no changes for that side
- A parsed qty of `0L` means remove the level — preserved as-is, not filtered
- `receivedTs` is set to `System.currentTimeMillis()` inside the parser

### 8.3 `parseTrade` output contract
- `aggressorSide` is derived from `m` (isBuyerMaker) field:
  `m=false → BUY`, `m=true → SELL`
- `exchangeTs` is set from the `T` field (trade time), not `E` (event time)
- `receivedTs` is set to `System.currentTimeMillis()` inside the parser

---

## 9. `SnapshotFetcher` — class specification

```
package com.muralis.ingestion

Constructor:
    SnapshotFetcher(InstrumentSpec instrumentSpec)

Public methods:
    OrderBookSnapshot fetch() throws SnapshotFetchException
```

### 9.1 Implementation rules
- Uses `java.net.http.HttpClient` — zero additional dependency
- Connect timeout: 10 seconds
- Read timeout: 10 seconds
- On HTTP status != 200: throw `SnapshotFetchException` with status code
- On timeout: throw `SnapshotFetchException` with cause
- On parse error: throw `SnapshotFetchException` with cause
- `SnapshotFetchException` is a checked exception defined in this package

### 9.2 `exchangeTs` for snapshot
Binance's REST snapshot response does not include a timestamp field in
the body. Use the HTTP response header `Date` if present, parsed to
epoch milliseconds. If not present, use `System.currentTimeMillis()` at
the moment the response body is fully received. Document this in a code
comment — it is a known approximation.

---

## 10. Logging specification

All logging uses SLF4J API (`org.slf4j.Logger`). Implementation is
Logback. Output is console only (Phase 1).

| Event | Level | Message format |
|---|---|---|
| WebSocket connected | INFO | `"[{}] WebSocket connected"`, symbol |
| Bootstrap complete | INFO | `"[{}] Order book synced. lastUpdateId={}"`, symbol, id |
| Gap detected | WARN | `"[{}] Gap detected: expected={}, got={}"`, symbol, expected, actual |
| Duplicate delta | DEBUG | `"[{}] Duplicate delta discarded. finalUpdateId={}"`, symbol, id |
| Reconnect attempt | INFO | `"[{}] Reconnecting, attempt {}. Reason: {}"`, symbol, n, reason |
| Parse error | ERROR | `"[{}] Parse error — triggering reconnect. Cause: {}"`, symbol, ex |
| WebSocket error | ERROR | `"[{}] WebSocket error: {}"`, symbol, ex.getMessage() |
| Queue drop | WARN | `"Queue rejected event — type={}"`, eventType |
| Disconnect requested | INFO | `"[{}] Disconnect requested. Closing."`, symbol |

No stack traces are logged at WARN level. Stack traces are logged at
ERROR level only.

---

## 11. What this spec explicitly excludes

The following are out of scope for `SPEC-ingestion.md` and must not be
implemented in the `ingestion/` package:

- Order book state (bids/asks map) — belongs in `engine/`
- Trade bubble state — belongs in `engine/`
- Any rendering logic — belongs in `ui/`
- Candle/footprint aggregation — belongs in `engine/`, Phase 2
- REST trade backfill (`aggTrades`) — Phase 2 only
- Coinbase, Rithmic, or CQG adapters — future providers
- Disk persistence of any kind — Phase 2

---

*Last updated: SPEC-ingestion.md v1.1 — Gap detection field renamed to lastPublishedFinalUpdateId for clarity. Symbol validation added to connect().*
*Next file: SPEC-engine.md*
