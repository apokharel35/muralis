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

## 2. Binance USDⓈ-M Futures WebSocket streams

> **ADR-001:** Switched from Binance Spot to Futures. See
> ARCHITECTURE.md Section 8 for rationale.

### 2.1 Connection URL
```
wss://fstream.binance.com/stream?streams=<symbol>@depth20@100ms/<symbol>@depth@100ms/<symbol>@aggTrade
```

For BTCUSDT:
```
wss://fstream.binance.com/stream?streams=btcusdt@depth20@100ms/btcusdt@depth@100ms/btcusdt@aggTrade
```

**Rules:**
- Symbol in the URL is **lowercase** (Binance requirement)
- **Three** streams are combined into a single WebSocket connection:
  - `@depth20@100ms` — partial book snapshot (top 20 levels), used for bootstrap only
  - `@depth@100ms` — incremental depth updates (diffs), used for live book maintenance
  - `@aggTrade` — aggregate trade events
- The combined stream wraps each message in an envelope:
  ```json
  { "stream": "btcusdt@depth@100ms", "data": { ... } }
  ```
- The `stream` field is used to route the `data` payload to the correct
  handler — depth20 handler, depth diff handler, or aggTrade handler

### 2.2 Depth stream (`@depth@100ms`)
Update frequency: every 100ms. Each message is a diff (delta) update.

**Raw message shape (Futures):**
```json
{
  "stream": "btcusdt@depth@100ms",
  "data": {
    "e": "depthUpdate",
    "E": 1234567891234,
    "T": 1234567891233,
    "s": "BTCUSDT",
    "U": 10041,
    "u": 10045,
    "pu": 10040,
    "b": [["97432.5", "1.234"], ["97431.0", "0.000"]],
    "a": [["97433.0", "0.500"]]
  }
}
```

**Field mapping to `OrderBookDelta`:**

| JSON field | Meaning | Maps to |
|---|---|---|
| `E` | Event time (ms) | `exchangeTs` |
| `T` | Transaction time (ms) | *ignored* — informational only |
| `s` | Symbol (uppercase) | `symbol` |
| `U` | First update ID in batch | `firstUpdateId` |
| `u` | Last update ID in batch | `finalUpdateId` |
| `pu` | Previous final update ID | *used by adapter for gap detection — NOT passed to engine* |
| `b` | Bid changes `[price, qty][]` | `bidPrices[]`, `bidQtys[]` |
| `a` | Ask changes `[price, qty][]` | `askPrices[]`, `askQtys[]` |

**`pu` field (Futures only):** The `pu` field links each delta to the
previous one. The adapter uses it for gap detection internally (see
Section 4). It is NOT added to the `OrderBookDelta` record — keeping
the model types provider-agnostic.

**Critical parsing rule:** A quantity of `"0.000"` means **remove
that price level**. It must be stored as `0L` in the delta. The engine
interprets `qty == 0L` as a deletion instruction. This is not a
zero-volume level — it is an absence instruction.

### 2.3 Aggregate trade stream (`@aggTrade`)
Delivered for every aggregate trade. Fills at the same price and same
taker side within 100ms are bundled into a single event.

**Raw message shape (Futures):**
```json
{
  "stream": "btcusdt@aggTrade",
  "data": {
    "e": "aggTrade",
    "E": 1234567891234,
    "s": "BTCUSDT",
    "a": 5933014,
    "p": "97432.5",
    "q": "0.041",
    "nq": "0.041",
    "f": 100,
    "l": 105,
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
| `a` | Aggregate trade ID | `tradeId` **(NOT `t` — Futures has no `t` field)** |
| `p` | Price (string) | `price` (via `parsePrice()`) |
| `q` | Aggregate quantity (string) | `qty` (via `parseQty()`) |
| `m` | isBuyerMaker | `aggressorSide` (via derivation rule) |
| `nq` | Normal qty (excludes RPI) | *ignored* — use `q` |
| `f` | First constituent trade ID | *ignored* |
| `l` | Last constituent trade ID | *ignored* |

**Critical field note:** Use `T` (trade time) for `exchangeTs`, not `E`
(event time). `T` is when the trade matched on the exchange. `E` is when
the event was published to the stream — always slightly later. For candle
and bubble timing, `T` is the correct timestamp.

**Critical field note:** Use `a` (aggregate trade ID) for `tradeId`, not
`t`. The Futures `@aggTrade` stream does not have a `t` field. The `a`
field serves the same purpose for deduplication in `TradeBuffer`.

**`AggressorSide` derivation (unchanged from Spot — same `m` semantics):**
```
m == false  →  AggressorSide.BUY   (buyer lifted the offer)
m == true   →  AggressorSide.SELL  (seller hit the bid)
```

---

## 3. Snapshot bootstrap sequence (WebSocket-only)

This is the most critical procedure in the ingestion layer. An incorrect
bootstrap produces a corrupted order book that will never self-correct.

> **ADR-001 Addendum:** The original design used a REST snapshot from
> `fapi.binance.com/fapi/v1/depth`. This endpoint is geo-blocked for
> US IPs (HTTP 451), same as Spot. The bootstrap was redesigned to use
> the `@depth20@100ms` WebSocket stream as the snapshot source. No REST
> call is required. See `ADR-001-binance-futures.md` for full analysis.

### 3.1 Full bootstrap sequence

```
Step 1. Open WebSocket connection to combined stream URL
        (three streams: @depth20@100ms, @depth@100ms, @aggTrade)

        During bootstrap (before sync):
        - IGNORE all @depth diff messages (do not buffer, do not publish)
        - @aggTrade messages MAY be published to the main queue
          immediately — they are not affected by snapshot sequencing
        - @depth20 messages are processed as snapshot candidates

Step 2. Publish ConnectionEvent(CONNECTING) to the main queue.

Step 3. Wait for the first @depth20@100ms message.
        Parse it as an OrderBookSnapshot:
        - bids and asks from the arrays (top 20 levels each)
        - lastUpdateId from the "u" field
        - exchangeTs from the "E" field
        Publish OrderBookSnapshot to the main queue.

Step 4. Set awaitingFirstDiff = true
        Set lastPublishedFinalUpdateId = snapshot's "u" value

Step 5. When the first @depth diff arrives after sync:
        - Accept it UNCONDITIONALLY (no pu validation)
        - Set lastPublishedFinalUpdateId = diff.finalUpdateId
        - Set awaitingFirstDiff = false
        - Publish diff to the main queue
        This anchors the diff chain. All subsequent diffs validate
        normally via pu.

Step 6. All subsequent @depth diffs follow normal gap detection
        (Section 4).

Step 7. IGNORE all @depth20 messages after sync — do not parse,
        do not process.

Step 8. Publish ConnectionEvent(CONNECTED) to the main queue.
```

### 3.2 Why the first diff is accepted unconditionally

The `@depth20` and `@depth` diff streams use **different update ID
sequences**. The `@depth20` stream's `u` value will never appear as
a diff's `pu` value. Attempting pu-chain validation between them
causes an infinite gap detection loop.

Accepting the first diff unconditionally may introduce a brief
(~100ms) inconsistency between the snapshot state and the first
diff. This self-corrects within one update cycle and is not visible
to the trader.

### 3.3 Why the book starts with 20 levels

The `@depth20` stream provides only the top 20 bid and 20 ask levels.
The full book builds out within 1-2 seconds as diffs add new price
levels beyond the initial 20. For a visualization tool this is
visually indistinguishable from a REST-based 1000-level bootstrap
after the first couple of seconds.

### 3.4 Sequence diagram

```
WebSocket (3 streams)                              main queue
    │                                                  │
    │──@depth20 (snapshot)─────── parse as snapshot ───▶│ (Step 3)
    │──@depth diff───────── accept unconditionally ───▶│ (Step 5, anchors chain)
    │──@aggTrade──────────────────────────────────────▶│ (trades always flow)
    │──@depth diff───── pu validation (normal) ──────▶│ (Step 6+)
    │──@depth20────────── IGNORED after sync           │
```

### 3.5 `@depth20@100ms` message format

```json
{
  "e": "depthUpdate",
  "E": 1571889248277,
  "T": 1571889248276,
  "s": "BTCUSDT",
  "U": 390497796,
  "u": 390497878,
  "pu": 390497794,
  "b": [
    ["67083.40", "0.002"],
    ["67083.30", "3.906"],
    ... (up to 20 levels)
  ],
  "a": [
    ["67083.50", "3.340"],
    ["67083.60", "4.525"],
    ... (up to 20 levels)
  ]
}
```

For bootstrap purposes:
- Use `u` as `lastUpdateId` for the snapshot
- Use `E` as `exchangeTs`
- Parse bids and asks with the existing `parsePrice()`/`parseQty()` methods
- `pu` field is ignored during bootstrap (only used in live diff validation)

### 3.6 `SnapshotFetcher` status

`SnapshotFetcher.java` remains in the codebase but is **not called**
during the bootstrap sequence. It is preserved for:
- Future use if a non-geo-blocked REST endpoint becomes available
- Non-US deployments where `fapi.binance.com` is accessible
- Testing against a local mock server

The REST endpoint details are:
```
URL:     https://fapi.binance.com/fapi/v1/depth
Params:  symbol=BTCUSDT&limit=1000
Status:  GEO-BLOCKED for US IPs (HTTP 451)
```

---

## 4. Gap detection

Once in live mode (Step 8 above), every incoming delta must be checked
for sequence continuity. The Futures depth stream includes a `pu`
(previous final update ID) field that enables direct chain validation.

### 4.1 Gap detection rule (using `pu` field)

Gap detection is performed by `BinanceAdapter` on the ingestion thread,
**before** publishing the delta to the main queue. The adapter tracks
the last published delta's `finalUpdateId` in a field called
`lastPublishedFinalUpdateId`.

```
For each incoming delta after the first:

    // Step 1: Check for duplicates first
    if delta.finalUpdateId <= lastPublishedFinalUpdateId:
        → Log: DEBUG "Duplicate delta received on {symbol}, discarding."
        → Discard silently. Do not reconnect.
        → Return

    // Step 2: Validate chain using pu field
    if delta.pu != lastPublishedFinalUpdateId:
        → GAP DETECTED
        → Log: WARN "Gap detected on {symbol}: expected pu={lastPublishedFinalUpdateId},
                got pu={delta.pu}. Triggering reconnect."
        → Publish ConnectionEvent(RECONNECTING, reason="Gap detected: ...")
        → Discard the corrupted delta (do not apply it)
        → Trigger reconnection sequence (Section 5)
        → Return — do not process further events until reconnected

    // Step 3: Valid delta — publish
    → Publish delta to main queue
    → lastPublishedFinalUpdateId = delta.finalUpdateId
```

### 4.2 Why `pu` is better than computing `expected = last + 1`

The Spot approach required: `delta.firstUpdateId == lastPublished + 1`.
The Futures `pu` field is explicitly set by Binance to equal the previous
delta's `u` value. This is a direct chain link — no arithmetic needed,
and no ambiguity about whether `firstUpdateId` should equal `last + 1`
or `last`.

### 4.3 What constitutes a gap

A gap is any delta where `pu` does not equal `lastPublishedFinalUpdateId`.
This covers:

- Missing updates (network packet loss) — `pu` will be ahead
- Out-of-order updates — `pu` won't match
- Duplicate updates — caught by the `finalUpdateId <=` check before
  `pu` validation

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
    reconnectCount   int                        (reset on CONNECTED)
    disconnectTs     long                       (Phase 2 placeholder, record always)
    lastPublishedFinalUpdateId  long            (last published finalUpdateId; -1 = none)
    awaitingFirstDiff  boolean                  (true after @depth20 sync, false after first diff)
    synced           boolean                    (false during bootstrap, true after sync)
    state            ConnectionState            (current state, volatile)

Public methods (from MarketDataProvider interface):
    void connect()        → starts bootstrap sequence on calling thread
    void disconnect()     → closes WebSocket, publishes DISCONNECTED, stops retrying
    void addListener(MarketDataListener l)

Private methods:
    void handleDepth20Message(JsonObject) → parse as snapshot during bootstrap, ignore after sync
    void handleDepthMessage(JsonObject)   → ignore during bootstrap, parse + validate after sync
    void handleAggTradeMessage(JsonObject) → parse + publish trade (always)
    void triggerReconnect(String)         → Section 5 procedure
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

if (stream.contains("@depth20")) {
    adapter.handleDepth20Message(data);
} else if (stream.endsWith("@depth@100ms")) {
    adapter.handleDepthMessage(data);
} else if (stream.endsWith("@aggTrade")) {
    adapter.handleAggTradeMessage(data);
} else {
    log.warn("Unknown stream type received: {}", stream);
}
```

**Note:** The `@depth20` check uses `contains()` (not `endsWith()`)
and is checked BEFORE `@depth@100ms` to prevent `@depth20@100ms`
from matching the `@depth@100ms` endsWith check.

---

## 8. `BinanceMessageParser` — class specification

```
package com.muralis.ingestion

Constructor:
    BinanceMessageParser(InstrumentSpec instrumentSpec)

Public methods:
    OrderBookDelta parseDelta(JsonObject data)
    long           parsePu(JsonObject data)      → extracts `pu` field for adapter gap detection
    NormalizedTrade parseAggTrade(JsonObject data)

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
- The `pu` field is NOT included in the `OrderBookDelta` record — it is
  returned separately via `parsePu()` for adapter-internal gap detection

### 8.3 `parseAggTrade` output contract
- `tradeId` is set from the `a` field (aggregate trade ID), NOT `t`
- `qty` is set from the `q` field (aggregate quantity of all fills)
- Fields `nq`, `f`, `l` are ignored — not relevant for visualization
- `aggressorSide` is derived from `m` (isBuyerMaker) field:
  `m=false → BUY`, `m=true → SELL`
- `exchangeTs` is set from the `T` field (trade time), not `E` (event time)
- `receivedTs` is set to `System.currentTimeMillis()` inside the parser

---

## 9. `SnapshotFetcher` — class specification (INACTIVE in Phase 1)

> **Not called during bootstrap.** The REST endpoint `fapi.binance.com`
> is geo-blocked for US IPs (HTTP 451). Bootstrap uses `@depth20`
> WebSocket stream instead (Section 3). This class is preserved for
> future use if REST access becomes available.

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
The Futures REST snapshot response includes an `E` (event time) field
in the response body. Use this directly as `exchangeTs`. This is an
improvement over Spot, where `E` was absent and we had to parse the
HTTP `Date` header as a fallback.

```java
long exchangeTs = responseJson.get("E").getAsLong();
```

If `E` is absent for any reason, fall back to
`System.currentTimeMillis()` and log a WARN.

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

*Last updated: SPEC-ingestion.md v1.3 — Bootstrap rewritten for WebSocket-only (@depth20 snapshot). REST endpoint geo-blocked. Three-stream connection. awaitingFirstDiff pattern. SnapshotFetcher marked inactive.*
*Next file: SPEC-engine.md*
