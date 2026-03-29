# DATA-CONTRACTS.md — Muralis

> This file is the single source of truth for every type, enum, constant,
> and invariant that crosses a module boundary. No spec file may define its
> own types. All spec files reference types defined here by exact name.
>
> **Claude Code instruction:** When generating any class in `com.muralis`,
> if a field represents price, quantity, timestamp, or direction — its type
> and scale MUST match the definition in this file exactly. Never infer or
> invent a type for these fields.

---

## 1. Primitive type rules (global, no exceptions)

These rules apply to every class, every method signature, and every field
in the entire codebase. They are not suggestions.

### 1.1 Price
- **Internal type:** `long` (fixed-point)
- **Scale:** `10^priceScale` where `priceScale` is defined per instrument in Section 5
- **Example:** BTC price `97432.51` with `priceScale=2` → stored as `9743251L`
- **Conversion to display:** `price / Math.pow(10, priceScale)` — only at render time
- **Forbidden types:** `double`, `float`, `BigDecimal` on any hot path
- **BigDecimal permitted only at:** JSON parse boundary and display formatting

### 1.2 Quantity
- **Internal type:** `long` (fixed-point)
- **Scale:** `10^qtyScale` where `qtyScale` is defined per instrument in Section 5
- **Example:** BTC qty `0.00041800` with `qtyScale=8` → stored as `41800L`
- **Conversion to display:** `qty / Math.pow(10, qtyScale)` — only at render time
- **Forbidden types:** `double`, `float`, `BigDecimal` on any hot path

### 1.3 Timestamp
- **Internal type:** `long`
- **Unit:** Unix milliseconds
- **Source:** Always the exchange-provided timestamp field from the raw message
- **Name convention:** Fields storing exchange time are named `exchangeTs`
- **Fields storing local receipt time are named:** `receivedTs`
- **Forbidden:** Using `System.currentTimeMillis()` as a substitute for
  `exchangeTs` anywhere in engine or rendering logic

### 1.4 Symbol
- **Internal type:** `String`
- **Format:** Uppercase, no separator — e.g. `"BTCUSDT"`, `"ETHUSDT"`
- **CME format:** Uppercase with hyphen-separated expiry — e.g. `"ES-H26"`, `"NQ-H26"`
- **Forbidden:** Lowercase symbols, slash-separated formats (`"BTC/USDT"`),
  or any runtime symbol transformation in engine or rendering code

### 1.5 Sequence ID
- **Internal type:** `long`
- **Semantics:** Monotonically increasing integer assigned by the exchange
  per order book update event. Used exclusively for gap detection.
- **Fields:** `firstUpdateId` (first ID in a delta batch), `finalUpdateId`
  (last ID in a delta batch), `lastUpdateId` (the ID of the last applied update)

---

## 2. Enums

### 2.1 `ConnectionState`
```
package com.muralis.model;

public enum ConnectionState {
    CONNECTING,     // Initial connection attempt in progress
    CONNECTED,      // WebSocket open, snapshot received, deltas flowing
    RECONNECTING,   // Connection lost; attempting to re-establish
    DISCONNECTED    // Explicitly stopped; no reconnection will be attempted
}
```

**Transitions:**
```
CONNECTING → CONNECTED       (snapshot received and applied successfully)
CONNECTING → RECONNECTING    (connection failed before snapshot)
CONNECTED  → RECONNECTING    (WebSocket closed unexpectedly or gap detected)
RECONNECTING → CONNECTING    (reconnect attempt started)
ANY        → DISCONNECTED    (explicit shutdown called)
```
No other transitions are valid. Any `ConnectionState` change must be
published to all registered `MarketDataListener` instances.

### 2.2 `AggressorSide`
```
package com.muralis.model;

public enum AggressorSide {
    BUY,    // Buyer was the aggressor — lifted the offer — isBuyerMaker = false
    SELL    // Seller was the aggressor — hit the bid  — isBuyerMaker = true
}
```

**Derivation rule (Binance-specific, defined once here, referenced everywhere):**
```
if (isBuyerMaker == false) → AggressorSide.BUY   // green bubble
if (isBuyerMaker == true)  → AggressorSide.SELL  // red bubble
```

This derivation happens **once**, inside `BinanceAdapter`, at parse time.
No other class may inspect `isBuyerMaker`. All downstream code uses
`AggressorSide` exclusively.

---

## 3. Shared event types

These are the normalized event types that cross module boundaries. They are
immutable value objects (no setters). All fields are `final`.

Every event type carries both `exchangeTs` and `receivedTs`. The engine
uses `exchangeTs` for all logic. `receivedTs` is recorded for latency
diagnostics only and must never influence engine or rendering behaviour.

---

### 3.1 `OrderBookSnapshot`
Delivered once per connection, after the REST snapshot is fetched and
validated. Represents the complete state of the order book at a point in time.

```
package com.muralis.model;

OrderBookSnapshot {
    String symbol           // e.g. "BTCUSDT"
    long   lastUpdateId     // Sequence ID of this snapshot (from REST response)
    long   exchangeTs       // Exchange-provided timestamp (ms). Use REST
                            // response server time if not in snapshot body.
    long   receivedTs       // System.currentTimeMillis() at receipt
    long[] bidPrices        // Parallel arrays. Index 0 = best bid.
    long[] bidQtys          // Quantity at bidPrices[i]. 0 means empty level.
    long[] askPrices        // Parallel arrays. Index 0 = best ask.
    long[] askQtys          // Quantity at askPrices[i]. 0 means empty level.
}
```

**Invariants:**
- `bidPrices.length == bidQtys.length` always
- `askPrices.length == askQtys.length` always
- `bidPrices` is sorted descending (best bid first)
- `askPrices` is sorted ascending (best ask first)
- All prices and quantities are in fixed-point per the instrument's
  `priceScale` and `qtyScale`
- `lastUpdateId > 0` always
- Array lengths may be 0 for an empty side (valid, not an error)

---

### 3.2 `OrderBookDelta`
Delivered continuously from the WebSocket depth stream. Represents an
incremental update to the order book.

```
package com.muralis.model;

OrderBookDelta {
    String symbol           // e.g. "BTCUSDT"
    long   firstUpdateId    // U field from Binance — first update ID in batch
    long   finalUpdateId    // u field from Binance — last update ID in batch
    long   exchangeTs       // E field from Binance message (event time, ms)
    long   receivedTs       // System.currentTimeMillis() at receipt
    long[] bidPrices        // Changed bid price levels (fixed-point)
    long[] bidQtys          // New quantity at bidPrices[i]. 0 = remove level.
    long[] askPrices        // Changed ask price levels (fixed-point)
    long[] askQtys          // New quantity at askPrices[i]. 0 = remove level.
}
```

**Invariants:**
- `firstUpdateId <= finalUpdateId` always
- `bidPrices.length == bidQtys.length` always
- `askPrices.length == askQtys.length` always
- A `bidQty` or `askQty` of `0L` means **remove that price level** from
  the book — this is not a zero-volume level, it is a deletion instruction
- Arrays may be empty (length 0) — a delta touching only bids has empty ask arrays
- `finalUpdateId` of each delta must equal `firstUpdateId - 1` of the
  next delta. A gap requires full book reconstruction.

---

### 3.3 `NormalizedTrade`
Delivered for every matched trade from the WebSocket trade stream.

```
package com.muralis.model;

NormalizedTrade {
    String       symbol        // e.g. "BTCUSDT"
    long         tradeId       // Exchange-assigned trade ID. Unique per session.
    long         price         // Fixed-point. Scale = instrument priceScale.
    long         qty           // Fixed-point. Scale = instrument qtyScale.
    AggressorSide aggressorSide // Derived from isBuyerMaker at parse time
    long         exchangeTs    // T field from Binance trade event (ms)
    long         receivedTs    // System.currentTimeMillis() at receipt
}
```

**Invariants:**
- `price > 0` always
- `qty > 0` always
- `tradeId > 0` always
- `aggressorSide` is never `null`
- `exchangeTs > 0` always
- Duplicate `tradeId` values within a session indicate a bug in the
  provider — the engine must log a warning and discard the duplicate

---

### 3.4 `InstrumentSpec`
Describes the fixed properties of a tradeable instrument. Supplied by the
provider at connection time. Immutable for the lifetime of a session.

```
package com.muralis.model;

InstrumentSpec {
    String symbol        // Canonical symbol, e.g. "BTCUSDT"
    int    priceScale    // Decimal places in price. BTC=2, ES=2, NQ=2.
    long   tickSize      // Minimum price increment in fixed-point.
                         // BTC: 1 (= 0.01), ES: 25 (= 0.25), NQ: 25 (= 0.25)
    int    qtyScale      // Decimal places in quantity. Crypto=8, Futures=0.
    long   minQty        // Minimum order quantity in fixed-point.
    String currency      // Settlement currency. e.g. "USDT", "USD"
    ProviderType provider // Which provider supplies this instrument
}
```

**Invariants:**
- `priceScale >= 0` always
- `tickSize > 0` always
- `qtyScale >= 0` always
- `minQty > 0` always
- `symbol` matches the canonical format defined in Section 1.4

---

### 3.5 `ConnectionEvent`
Published whenever the connection state changes. Consumed by the UI to
display connection status and by the engine to trigger book reconstruction.

```
package com.muralis.model;

ConnectionEvent {
    String          symbol         // Which instrument this event relates to
    ConnectionState state          // New state (see Section 2.1)
    long            receivedTs     // System.currentTimeMillis() at event time
    String          reason         // Human-readable reason string. Never null.
                                   // e.g. "Gap detected: expected 10041, got 10045"
                                   //      "WebSocket closed by remote: code 1001"
                                   //      "Explicit shutdown requested"
}
```

---

## 4. Enums (provider — remaining in `com.muralis.provider`)

> **Note:** `ConnectionState` and `ConnectionEvent` were moved to
> `com.muralis.model` so that the `MarketEvent` sealed interface can
> include `ConnectionEvent` in its `permits` clause without creating a
> circular dependency. `ProviderType` remains in `provider/` because it
> is not a queue event type.

### 4.1 `ProviderType`
```
package com.muralis.provider;

public enum ProviderType {
    BINANCE_SPOT,       // Binance Spot WebSocket (Phase 1)
    BINANCE_FUTURES,    // Binance USD-M Futures WebSocket (future)
    CME_RITHMIC,        // CME via Rithmic R|Protocol (future)
    CME_CQG,            // CME via CQG WebAPI (future)
    COINBASE_ADVANCED   // Coinbase Advanced Trade WebSocket (future)
}
```

---

## 5. Instrument specifications (known instruments)

All prices and quantities in this section are expressed in **fixed-point**
using the instrument's own `priceScale` and `qtyScale`.

### 5.1 Binance Spot — active in Phase 1

| Field | BTCUSDT | ETHUSDT |
|---|---|---|
| `symbol` | `"BTCUSDT"` | `"ETHUSDT"` |
| `priceScale` | `2` | `2` |
| `tickSize` | `1L` (= 0.01) | `1L` (= 0.01) |
| `qtyScale` | `8` | `8` |
| `minQty` | `100L` (= 0.000001 BTC) | `100L` (= 0.000001 ETH) |
| `currency` | `"USDT"` | `"USDT"` |
| `provider` | `BINANCE_SPOT` | `BINANCE_SPOT` |

**Conversion examples for BTCUSDT:**
```
Raw JSON price  "97432.51"  → parse to BigDecimal at boundary
                            → multiply by 10^2 → 9743251L  (stored)
                            → divide by 10^2 at render     → "97,432.51"

Raw JSON qty    "0.00041800" → parse to BigDecimal at boundary
                             → multiply by 10^8 → 41800L   (stored)
                             → divide by 10^8 at render    → "0.00041800"
```

### 5.2 CME Futures — placeholder, Phase 2+

> These specs are stubs. They are included to establish the data model for
> CME instruments and to confirm that `InstrumentSpec` is expressive enough.
> No Phase 1 code should reference these. No `BinanceAdapter` logic should
> branch on CME instrument types.

| Field | ES (E-mini S&P 500) | NQ (E-mini Nasdaq-100) |
|---|---|---|
| `symbol` | `"ES-H26"` | `"NQ-H26"` |
| `priceScale` | `2` | `2` |
| `tickSize` | `25L` (= 0.25 points) | `25L` (= 0.25 points) |
| `qtyScale` | `0` | `0` |
| `minQty` | `1L` (= 1 contract) | `1L` (= 1 contract) |
| `currency` | `"USD"` | `"USD"` |
| `provider` | `CME_RITHMIC` (placeholder) | `CME_RITHMIC` (placeholder) |

**CME-specific notes (for Phase 2 implementer):**
- ES tick value: $12.50 per tick (0.25 points × $50 multiplier)
- NQ tick value: $5.00 per tick (0.25 points × $20 multiplier)
- Symbol format includes expiry month code: H=March, M=June, U=September, Z=December
- CME has defined trading sessions; unlike crypto, the book is not 24/7
- Price format from Rithmic/CQG is Protocol Buffers, not JSON — the
  `BinanceAdapter` parse logic must not be reused for CME

---

## 6. Queue contract

The internal event queue that connects the ingestion thread to the engine
thread must satisfy the following contract. The concrete implementation is
specified in `SPEC-ingestion.md`. This section defines only the behavioural
contract that both sides agree on.

```
Queue carries: OrderBookSnapshot | OrderBookDelta | NormalizedTrade | ConnectionEvent

Producer (ingestion thread):
  - Publishes events in the exact order they are received from the exchange
  - Never publishes an OrderBookDelta before the first OrderBookSnapshot
    for that symbol
  - Publishes a ConnectionEvent(RECONNECTING) before discarding any
    buffered events on reconnect
  - Phase 1 uses LinkedTransferQueue which is unbounded — offer() never
    blocks or rejects. The defensive logging for rejected events exists
    in anticipation of Phase 2's bounded queue replacement (Disruptor).
    If the queue is ever replaced with a bounded queue, the producer
    must not block for more than 5ms and must drop the event with a
    warning log on rejection.

Consumer (engine thread):
  - Processes events strictly in publish order
  - Never calls any JavaFX or UI method
  - Completes processing of each event in < 1ms under normal conditions
```

**Permitted queue event ordering within a session:**
```
ConnectionEvent(CONNECTING)
→ OrderBookSnapshot
→ OrderBookDelta (zero or more)
→ NormalizedTrade (interleaved with deltas)
→ ConnectionEvent(RECONNECTING)   ← if gap or disconnect occurs
→ ConnectionEvent(CONNECTING)     ← reconnect attempt
→ OrderBookSnapshot               ← fresh snapshot after reconnect
→ OrderBookDelta (zero or more)
→ ...
→ ConnectionEvent(DISCONNECTED)   ← on explicit shutdown
```

---

## 7. Parsing boundary rules

These rules define exactly where raw exchange data (JSON strings, byte
arrays) is converted to internal types. Conversion must happen in one place
only — the adapter — and must not leak raw strings into the engine or UI.

| Boundary | Permitted | Forbidden |
|---|---|---|
| JSON → internal model | `BigDecimal` for intermediate parse, then to `long` | `Double.parseDouble()` — loses precision |
| Internal model → display | `long` → `BigDecimal` for formatting only | Any arithmetic on `double` derived from price |
| Internal model → engine | `long` fields only | Passing raw JSON strings or `BigDecimal` objects |
| Engine → UI | Snapshot of `long` fields only | Passing live mutable data structures to UI thread |

**Canonical price parse pattern (used in BinanceAdapter only):**
```java
// Raw JSON field: "97432.51", priceScale: 2
long parsePrice(String raw, int priceScale) {
    return new BigDecimal(raw)
        .movePointRight(priceScale)
        .longValueExact();   // throws if precision lost — a bug, not an error
}
```

**Canonical quantity parse pattern (used in BinanceAdapter only):**
```java
// Raw JSON field: "0.00041800", qtyScale: 8
long parseQty(String raw, int qtyScale) {
    return new BigDecimal(raw)
        .movePointRight(qtyScale)
        .longValueExact();   // throws if precision lost — a bug, not an error
}
```

The use of `longValueExact()` is intentional. If Binance ever sends a
value that cannot be represented exactly at the configured scale, it is a
data contract violation that must surface immediately as an exception, not
silently corrupt data.

---

## 8. Invariant summary (Claude Code enforcement checklist)

When generating any class in `com.muralis`, verify each item before
considering the implementation complete:

- [ ] No `double` or `float` field stores a price or quantity
- [ ] No `System.currentTimeMillis()` is used as a substitute for `exchangeTs`
- [ ] No raw JSON string or `BigDecimal` is passed beyond the adapter boundary
- [ ] No UI method (`Platform.runLater`, any JavaFX type) is called from
      the ingestion thread or engine thread
- [ ] No blocking I/O (network, disk, sleep) occurs on the UI thread
- [ ] Every `AggressorSide` is derived from `isBuyerMaker` inside
      `BinanceAdapter` only — no other class inspects this raw field
- [ ] Every `ConnectionState` transition matches the valid transitions in Section 2.1
- [ ] Quantity `0L` in an `OrderBookDelta` means remove the level — not zero volume
- [ ] Duplicate `tradeId` within a session is logged as a warning and discarded
- [ ] `longValueExact()` is used in all price and quantity parse calls

---

*Last updated: DATA-CONTRACTS.md v1.1 — ConnectionState and ConnectionEvent moved to model/ package. Queue backpressure clarified for Phase 1.*
*Next file: ARCHITECTURE.md*
