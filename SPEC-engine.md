# SPEC-engine.md — Muralis

> This spec defines the `engine/` package in full. It covers the engine
> thread loop, order book state management, trade buffer, render snapshot
> construction, and gap recovery behaviour.
>
> **Claude Code instruction:** All classes generated from this spec live
> in `com.muralis.engine`. They may import from `com.muralis.model` and
> `com.muralis.provider` only. No imports from `com.muralis.ingestion`
> or `com.muralis.ui` are permitted under any circumstance. The one
> exception is `RenderSnapshot`, which is in `com.muralis.engine` and
> is imported by `com.muralis.ui`. See `ARCHITECTURE.md` Section 4.

---

## 1. Scope

This spec covers exactly the following classes:

| Class | Responsibility |
|---|---|
| `OrderBookEngine` | Engine thread loop. Consumes queue. Orchestrates all state. |
| `OrderBook` | Mutable bid/ask state. Owned exclusively by the engine thread. |
| `TradeBuffer` | Fixed-capacity ring buffer of recent `TradeBlip` records. |
| `RenderSnapshot` | Immutable value object written by engine, read by UI thread. |
| `RenderConfig` | Mutable configuration owned by UI thread. Read by engine thread. |

No other classes belong in `com.muralis.engine`. Helper logic is
expressed as private methods, not additional top-level classes.

---

## 2. `OrderBookEngine` — class specification

### 2.1 Overview

`OrderBookEngine` owns the engine thread. It runs a consume loop that
reads `MarketEvent` instances from the `LinkedTransferQueue`, dispatches
each to the appropriate handler, and after each dispatch writes a new
`RenderSnapshot` to the `AtomicReference`.

```
package com.muralis.engine

Constructor:
    OrderBookEngine(
        LinkedTransferQueue<MarketEvent>  queue,
        AtomicReference<RenderSnapshot>   snapshotRef,
        InstrumentSpec                    instrumentSpec,
        RenderConfig                      renderConfig
    )

Public methods:
    void start()      → creates and starts the engine thread
    void stop()       → signals the engine thread to exit cleanly

Private fields:
    queue             LinkedTransferQueue<MarketEvent>
    snapshotRef       AtomicReference<RenderSnapshot>
    instrumentSpec    InstrumentSpec
    renderConfig      RenderConfig
    orderBook         OrderBook
    tradeBuffer       TradeBuffer
    running           volatile boolean
    lastSnapshotTs    long   (exchangeTs of the last applied event)
    connectionState   ConnectionState
```

### 2.2 Engine thread loop

```java
private void runLoop() {
    while (running) {
        MarketEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
        if (event == null) continue;  // timeout — loop and check running flag

        switch (event) {
            case OrderBookSnapshot s -> applySnapshot(s);
            case OrderBookDelta    d -> applyDelta(d);
            case NormalizedTrade   t -> applyTrade(t);
            case ConnectionEvent   c -> applyConnectionEvent(c);
        }

        snapshotRef.set(buildSnapshot());
    }
}
```

**Rules:**
- `buildSnapshot()` is called after **every** event, not batched.
  At 50–100 events/sec this is ~100 snapshot builds/sec. Each build
  is a shallow copy of existing arrays — acceptable cost.
- The `poll(100ms)` timeout allows the `running` flag to be checked
  regularly so `stop()` is responsive even during quiet markets.
- No event is skipped. No event is processed out of order.
- `snapshotRef.set()` is the only write that crosses the thread boundary.
  It is an atomic reference — no locking required.

### 2.3 `applySnapshot(OrderBookSnapshot s)`

Called when the ingestion layer completes the bootstrap sequence.

```
1. Reset orderBook to empty state.
2. For each bid level in s.bidPrices / s.bidQtys:
       orderBook.setBid(s.bidPrices[i], s.bidQtys[i])
3. For each ask level in s.askPrices / s.askQtys:
       orderBook.setAsk(s.askPrices[i], s.askQtys[i])
4. Set lastSnapshotTs = s.exchangeTs
5. Log: DEBUG "[{}] Snapshot applied. levels=bid:{} ask:{}"
         symbol, bidCount, askCount
```

**Invariant:** After `applySnapshot`, `orderBook` contains exactly the
levels present in the snapshot. No previous state survives.

### 2.4 `applyDelta(OrderBookDelta d)`

Called for each incremental update after the snapshot is applied.

```
1. For each bid change (d.bidPrices[i], d.bidQtys[i]):
       if d.bidQtys[i] == 0L:
           orderBook.removeBid(d.bidPrices[i])
       else:
           orderBook.setBid(d.bidPrices[i], d.bidQtys[i])

2. For each ask change (d.askPrices[i], d.askQtys[i]):
       if d.askQtys[i] == 0L:
           orderBook.removeAsk(d.askPrices[i])
       else:
           orderBook.setAsk(d.askPrices[i], d.askQtys[i])

3. Set lastSnapshotTs = d.exchangeTs
```

**Critical rule:** `qty == 0L` means remove the level. This is not a
zero-volume resting order. It is a deletion. See `DATA-CONTRACTS.md`
Section 3.2. Never insert a level with qty `0L` into the order book.

### 2.5 `applyTrade(NormalizedTrade t)`

Called for each matched trade from the trade stream.

```
1. Check for duplicate tradeId:
       if tradeBuffer.containsTradeId(t.tradeId):
           log WARN "[{}] Duplicate tradeId={} discarded", symbol, t.tradeId
           return

2. Create TradeBlip from NormalizedTrade:
       blip = new TradeBlip(t.tradeId, t.price, t.qty, t.aggressorSide, t.exchangeTs, t.receivedTs)

3. tradeBuffer.add(blip)
```

No order book mutation. Trades do not affect resting liquidity state.

### 2.6 `applyConnectionEvent(ConnectionEvent c)`

```
switch (c.state()) {
    case CONNECTING    → connectionState = CONNECTING
                         orderBook.clear()
                         tradeBuffer.clear()
                         log INFO "[{}] Connecting...", symbol

    case CONNECTED     → connectionState = CONNECTED
                         log INFO "[{}] Connected and synced.", symbol

    case RECONNECTING  → connectionState = RECONNECTING
                         orderBook.clear()
                         tradeBuffer.clear()
                         log WARN "[{}] Reconnecting. Reason: {}", symbol, c.reason()

    case DISCONNECTED  → connectionState = DISCONNECTED
                         running = false
                         log INFO "[{}] Disconnected.", symbol
}
```

**On `RECONNECTING` and `CONNECTING`:** The order book and trade buffer
are cleared immediately. The UI will receive a `RenderSnapshot` with
empty bid/ask arrays and no trade blips. The `connectionState` field
in the snapshot signals the UI to render the status indicator.

### 2.7 `buildSnapshot()`

Constructs the immutable `RenderSnapshot` from current engine state.
Called after every event. Must complete in < 1ms.

```
1. Read full bid book: bidEntries = orderBook.getBidsDescending()
2. Read full ask book: askEntries = orderBook.getAsksAscending()
3. Convert to parallel long[] arrays (defensive copies)
4. Read active blips from tradeBuffer:
       activeBlips = tradeBuffer.getActive(renderConfig.bubbleDecayMs())
5. Return new RenderSnapshot(
       symbol,
       lastSnapshotTs,
       bidPrices[], bidQtys[],
       askPrices[], askQtys[],
       List.copyOf(activeBlips),
       connectionState,
       instrumentSpec
   )
```

**Full book to UI:** The snapshot carries all levels from the order book
with no depth cap applied here. The renderer trims to visible levels
based on scroll position and canvas height. This keeps the engine
decoupled from rendering geometry decisions.

---

## 3. `OrderBook` — class specification

`OrderBook` is a mutable data structure owned exclusively by the engine
thread. It is never accessed from any other thread. No synchronisation
is needed or permitted.

### 3.1 Internal structure

```
package com.muralis.engine

private final TreeMap<Long, Long> bids  // key=price (fixed-point), value=qty
                                        // Sorted in DESCENDING order (best bid first)
private final TreeMap<Long, Long> asks  // key=price (fixed-point), value=qty
                                        // Sorted in ASCENDING order (best ask first)
```

`TreeMap` with `Comparator.reverseOrder()` for bids, natural order for asks.
This gives O(log n) insert, remove, and lookup. For a live order book with
hundreds to low thousands of levels, this is optimal — no hashing overhead,
iteration is always sorted.

### 3.2 Public methods

```java
void   setBid(long price, long qty)     // Insert or update bid level
void   setAsk(long price, long qty)     // Insert or update ask level
void   removeBid(long price)            // Remove bid level (qty reached 0)
void   removeAsk(long price)            // Remove ask level (qty reached 0)
long   getBidQty(long price)            // Returns 0L if level absent
long   getAskQty(long price)            // Returns 0L if level absent
long   bestBid()                        // First key of bids map; -1L if empty
long   bestAsk()                        // First key of asks map; -1L if empty
int    bidDepth()                       // Number of active bid levels
int    askDepth()                       // Number of active ask levels
void   clear()                          // Remove all levels from both sides

// For snapshot construction — returns entry set views for array conversion:
Set<Map.Entry<Long,Long>> getBidsDescending()   // Backed by bids TreeMap
Set<Map.Entry<Long,Long>> getAsksAscending()    // Backed by asks TreeMap
```

### 3.3 Invariants

- `setBid` or `setAsk` with `qty == 0L` must **never** be called.
  The caller (`applyDelta`) is responsible for routing zero-qty updates
  to `removeBid`/`removeAsk`. This is enforced with an assertion:
  ```java
  assert qty > 0L : "setBid/setAsk called with qty=0. Use remove instead.";
  ```
- `removeBid`/`removeAsk` on a non-existent price is a no-op. No exception.
- `bestBid()` always returns a value less than `bestAsk()` when both
  sides are non-empty. A crossed book (bid >= ask) indicates a data
  error — log WARN but do not throw.
- `getBidsDescending()` and `getAsksAscending()` return live views of
  the underlying `TreeMap`. They must only be called from the engine
  thread during `buildSnapshot()`. They must not be held beyond the
  scope of a single `buildSnapshot()` call.

### 3.4 Memory behaviour

A live BTC/USDT order book typically holds 500–2,000 active price levels
per side. Each `TreeMap` entry is a `Long` key + `Long` value + tree node
overhead ≈ 48 bytes. At 2,000 levels per side:

```
2 sides × 2,000 levels × 48 bytes ≈ 192 KB
```

This is negligible. No eviction, no pruning, no capacity limit is needed
on the `OrderBook` itself. Stale levels are evicted naturally when Binance
sends a delta with `qty=0` for that price.

---

## 4. `TradeBuffer` — class specification

`TradeBuffer` holds recent `TradeBlip` records for bubble rendering.
It is owned exclusively by the engine thread. No synchronisation needed.

### 4.1 Structure

```
package com.muralis.engine

private final ArrayDeque<TradeBlip> blips
private final HashSet<Long>         seenTradeIds   // For duplicate detection
private static final int            MAX_BLIPS = 500
```

`ArrayDeque` provides O(1) add to tail and O(1) remove from head — ideal
for a rolling window. `HashSet<Long>` for O(1) duplicate detection by
`tradeId`.

### 4.2 Public methods

```java
void            add(TradeBlip blip)
boolean         containsTradeId(long tradeId)
List<TradeBlip> getActive(long decayMs)   // Returns blips within the decay window
void            clear()
```

### 4.3 `add(TradeBlip blip)` behaviour

```
1. If size >= MAX_BLIPS:
       TradeBlip evicted = blips.removeFirst()
       seenTradeIds.remove(evicted.tradeId())
       log DEBUG "TradeBuffer at capacity, evicting oldest blip"

2. blips.addLast(blip)
3. seenTradeIds.add(blip.tradeId())
```

`MAX_BLIPS = 500` is a hard ceiling. At 50 trades/sec on a busy BTC
market, 500 blips represents 10 seconds of history — well beyond any
decay window. This bounds memory usage to:
```
500 blips × ~80 bytes per TradeBlip record ≈ 40 KB
```

### 4.4 `getActive(long decayMs)` behaviour

```
long cutoffTs = System.currentTimeMillis() - decayMs

Return all blips where blip.receivedTs >= cutoffTs

Implementation:
    Since blips are ordered oldest-first (addLast), iterate from the
    head forward. Skip blips where receivedTs < cutoffTs. Once a blip
    passes the cutoff, collect it and all remaining blips (they are
    guaranteed to be newer). Return as an unmodifiable List.
```

**Note:** `getActive` uses `receivedTs` for the cutoff — matching the
UI's `bubbleAlpha()` which also uses `receivedTs`. This ensures the
engine and UI agree on which blips are "active." The cutoff uses
`System.currentTimeMillis()` because bubble decay is a display concern,
not a data concern.

### 4.5 `seenTradeIds` memory management

`seenTradeIds` is bounded by `MAX_BLIPS`. When a blip is evicted from
the `ArrayDeque`, its `tradeId` is removed from `seenTradeIds`. This
prevents unbounded growth of the duplicate detection set. A tradeId
evicted from the set can theoretically cause a very old duplicate to
slip through — this is acceptable. Duplicate trades from Binance are
extremely rare and the consequence is a spurious bubble at an old price.

---

## 5. `RenderSnapshot` — class specification

Defined in `ARCHITECTURE.md` Section 6. Reproduced here for completeness
with additional implementation constraints.

```java
package com.muralis.engine;

public record RenderSnapshot(
    String          symbol,
    long            exchangeTs,
    long[]          bidPrices,
    long[]          bidQtys,
    long[]          askPrices,
    long[]          askQtys,
    List<TradeBlip> recentTrades,
    ConnectionState connectionState,
    InstrumentSpec  instrumentSpec
) {}
```

```java
package com.muralis.engine;

public record TradeBlip(
    long          tradeId,
    long          price,
    long          qty,
    AggressorSide aggressorSide,
    long          exchangeTs,
    long          receivedTs     // Local receipt time — used for bubble decay
                                 // calculation to avoid clock skew between
                                 // exchange time and local time.
                                 // Copied from NormalizedTrade.receivedTs.
) {}
```

### 5.1 Construction invariants

- `bidPrices` and `bidQtys` are always the same length
- `askPrices` and `askQtys` are always the same length
- Both arrays are **defensive copies** — never expose the `OrderBook`'s
  internal arrays directly
- `bidPrices` is sorted descending (index 0 = best bid)
- `askPrices` is sorted ascending (index 0 = best ask)
- `recentTrades` is `List.copyOf(...)` — unmodifiable
- When `connectionState` is `CONNECTING` or `RECONNECTING`, all price
  arrays have length 0 and `recentTrades` is empty
- `exchangeTs` is 0L when no event has been applied yet (initial state
  before first snapshot)

### 5.2 Null snapshot sentinel

Before the engine produces its first snapshot (between `start()` and
the first `OrderBookSnapshot` event), `snapshotRef.get()` returns
`null`. The UI must handle `null` gracefully by rendering a
"Connecting..." placeholder. This is the only null that crosses the
engine-UI boundary.

---

## 6. `RenderConfig` — class specification

`RenderConfig` is owned and mutated by the UI thread (via the decay
window slider). It is read by the engine thread during `buildSnapshot()`.
All fields are `volatile` to ensure visibility across threads without
requiring synchronisation.

```java
package com.muralis.engine;

public class RenderConfig {
    private volatile long bubbleDecayMs = 5_000L;  // Default: 5 seconds

    public long bubbleDecayMs()                { return bubbleDecayMs; }
    public void setBubbleDecayMs(long decayMs) { this.bubbleDecayMs = decayMs; }
}
```

### 6.1 Slider constraints (enforced by UI — not by RenderConfig)

| Constraint | Value |
|---|---|
| Minimum decay | 1,000ms (1 second) |
| Maximum decay | 30,000ms (30 seconds) |
| Default decay | 5,000ms (5 seconds) |
| Slider step | 500ms increments |

`RenderConfig` does not validate these bounds. The UI slider enforces
them. `RenderConfig` is a plain volatile field — not a clamped setter.

### 6.2 Why `volatile` is sufficient here

`bubbleDecayMs` is a single `long` field. On 64-bit JVMs (all modern
desktop JVMs), reads and writes of `long` are atomic under the Java
Memory Model when declared `volatile`. There is exactly one writer (UI
thread) and one reader (engine thread). No compound operations are
performed. `volatile` gives us visibility without the overhead of
`AtomicLong` or `synchronized`.

---

## 7. Array construction in `buildSnapshot()`

Converting the `TreeMap` entries to parallel `long[]` arrays is the
hottest code path in the engine. It runs after every event.

```java
private long[][] toArrays(Set<Map.Entry<Long, Long>> entries) {
    int size = entries.size();
    long[] prices = new long[size];
    long[] qtys   = new long[size];
    int i = 0;
    for (Map.Entry<Long, Long> entry : entries) {
        prices[i] = entry.getKey();
        qtys[i]   = entry.getValue();
        i++;
    }
    return new long[][]{ prices, qtys };
}
```

**Performance note:** This allocates two `long[]` arrays on every event.
At 100 events/sec with 1,000 levels each, this is 200 short-lived array
allocations/sec — well within ZGC's generational collection throughput
(see BUILD.md Section 4 for JVM flags). No object pooling is needed in
Phase 1. If GC pressure becomes visible in profiling, the upgrade path
is to pre-allocate fixed-size arrays and reuse them, copying only the
length that changed.

**Batching optimisation (optional for Phase 1):** The current design
calls `buildSnapshot()` after every single event. At 100 events/sec but
only 60 FPS on the UI, ~40 snapshots/sec are built and never read. If
profiling shows this is wasteful, a simple batching loop is acceptable:
drain all available events from the queue before building one snapshot.
Add a batch ceiling (e.g. 50 events) to prevent starvation of the
snapshot write during market spikes.

---

## 8. Engine startup and shutdown

### 8.1 Startup sequence

Called from `Application.main()`:

```
1. Instantiate OrderBook, TradeBuffer, RenderConfig
2. Instantiate OrderBookEngine(queue, snapshotRef, instrumentSpec, renderConfig)
3. Call engine.start()
   → Creates Thread("muralis-engine")
   → Sets thread.setDaemon(false)  ← engine must complete shutdown cleanly
   → Calls thread.start()
4. Engine thread enters runLoop() and blocks on queue.poll()
5. BinanceAdapter.connect() is called (from Application.main())
6. First ConnectionEvent(CONNECTING) arrives in queue
7. Engine wakes, processes event, writes first RenderSnapshot to snapshotRef
8. JavaFX Application starts, reads snapshotRef on first AnimationTimer pulse
```

### 8.2 Shutdown sequence

Triggered by JavaFX window close event (from `MuralisApp`):

```
1. MuralisApp calls adapter.disconnect()
2. BinanceAdapter closes WebSocket, publishes ConnectionEvent(DISCONNECTED)
3. Engine consumes DISCONNECTED event → sets running = false
4. Engine loop exits after current iteration completes
5. Engine thread terminates naturally (no interrupt needed)
6. JavaFX Platform.exit() is called
7. JVM exits cleanly
```

**No `Thread.interrupt()` is used.** The `running` flag and the
`DISCONNECTED` event are the shutdown signals. This avoids
`InterruptedException` handling complexity and ensures the engine
always finishes processing the current event before stopping.

---

## 9. What this spec explicitly excludes

The following are out of scope for `SPEC-engine.md` and must not be
implemented in the `engine/` package:

- Footprint candle accumulation — Phase 2
- Per-candle buy/sell volume tracking — Phase 2
- Delta (buy vol - sell vol) calculation — Phase 2
- Historical candle storage — Phase 2
- TPO / Market Profile logic — Phase 2
- Any WebSocket or network logic — belongs in `ingestion/`
- Any canvas drawing or JavaFX call — belongs in `ui/`
- Any disk I/O — Phase 2

---

## 10. Invariant checklist (Claude Code enforcement)

When generating any class in `com.muralis.engine`, verify:

- [ ] `OrderBook` is never accessed from outside the engine thread
- [ ] `TradeBuffer` is never accessed from outside the engine thread
- [ ] `snapshotRef.set()` is the only engine → UI data crossing
- [ ] `RenderSnapshot` fields are all defensive copies (arrays, lists)
- [ ] `qty == 0L` in a delta routes to `remove`, never to `set`
- [ ] `buildSnapshot()` is called after every event without exception
- [ ] `runLoop()` poll timeout is 100ms — not 0 (spin) and not Long.MAX_VALUE
- [ ] No JavaFX import exists anywhere in `com.muralis.engine`
- [ ] No `com.muralis.ingestion` import exists anywhere in `com.muralis.engine`
- [ ] `RenderConfig.bubbleDecayMs` is `volatile`
- [ ] Engine thread is named `"muralis-engine"` and is not a daemon thread

---

*Last updated: SPEC-engine.md v1.1 — TradeBlip.receivedTs added for clock-skew-safe decay. getActive() uses receivedTs. GC reference corrected to ZGC. Batching optimisation documented.*
*Next file: SPEC-rendering.md*
