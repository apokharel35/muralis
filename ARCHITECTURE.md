# ARCHITECTURE.md — Muralis

> This file defines the module boundaries, thread ownership, data flow,
> and dependency rules for the entire Muralis codebase. No implementation
> decision may contradict this file without a written ADR appended to
> Section 8. All spec files must respect the boundaries defined here.
>
> **Claude Code instruction:** Before generating any class, locate its
> package in Section 3 and verify that its imports respect the dependency
> rules in Section 4. A class may only import from packages it is permitted
> to depend on. Violations are architecture bugs, not style issues.

---

## 1. System context

Muralis is a single-process, single-JVM desktop application. There is no
server, no REST API, no embedded Tomcat, and no inter-process communication.
All data flows from one WebSocket connection through an in-memory pipeline
to a JavaFX canvas.

```
 Binance WebSocket
        │
        ▼
 ┌─────────────────┐
 │  Ingestion Layer │  (WebSocket thread — owned by Java-WebSocket library)
 │  BinanceAdapter  │  Parses JSON → normalized events → publishes to queue
 └────────┬────────┘
          │  LinkedTransferQueue<MarketEvent>
          ▼
 ┌─────────────────┐
 │  Engine Layer    │  (Single dedicated engine thread — owned by Muralis)
 │  OrderBookEngine │  Consumes queue → maintains OrderBook state
 └────────┬────────┘
          │  Volatile snapshot (read by UI thread)
          ▼
 ┌─────────────────┐
 │  UI Layer        │  (JavaFX Application Thread)
 │  LadderCanvas    │  AnimationTimer at 60 FPS → reads snapshot → paints
 └─────────────────┘
```

---

## 2. Thread model

There are exactly **three threads** in Phase 1. No additional threads may
be introduced without a written ADR in Section 8.

### 2.1 Ingestion thread
- **Owner:** Java-WebSocket library (`WebSocketClient` internal thread)
- **Name:** Set via `setConnectionLostTimeout` — not directly nameable
- **Responsibilities:**
    - Receive raw WebSocket frames
    - Parse JSON to normalized event types (defined in `DATA-CONTRACTS.md`)
    - Publish events to the `LinkedTransferQueue`
    - Perform snapshot bootstrap sequence (see `SPEC-ingestion.md`)
- **Forbidden on this thread:**
    - Any JavaFX call (`Platform.runLater` or direct)
    - Any blocking I/O beyond the WebSocket itself
    - Any order book state mutation
    - Any rendering logic

### 2.2 Engine thread
- **Owner:** Muralis — started in `Application.main()`, named `"muralis-engine"`
- **Type:** Plain `Thread` — not a virtual thread, not a pool thread
- **Lifecycle:** Started once at application start. Runs until
  `ConnectionState.DISCONNECTED` is received, then exits cleanly.
- **Responsibilities:**
    - Consume events from `LinkedTransferQueue` in strict publish order
    - Maintain the live `OrderBook` state (bids, asks, last update ID)
    - Detect sequence gaps and publish `ConnectionEvent(RECONNECTING)`
    - Maintain the `TradeBuffer` (recent trades for bubble rendering)
    - Write atomic snapshots to the `RenderSnapshot` for UI consumption
- **Forbidden on this thread:**
    - Any JavaFX call (`Platform.runLater` or direct)
    - Any blocking network or disk I/O
    - Any operation taking > 1ms under normal market conditions

### 2.3 UI thread (JavaFX Application Thread)
- **Owner:** JavaFX runtime
- **Responsibilities:**
    - Run the `AnimationTimer` at 60 FPS
    - Read the latest `RenderSnapshot` (atomic, non-blocking)
    - Paint the DOM ladder and trade bubbles to the canvas
    - Handle mouse and keyboard input
- **Forbidden on this thread:**
    - Any blocking operation (network, disk, sleep, queue poll with timeout)
    - Any order book state mutation
    - Any direct field access on mutable engine objects

### 2.4 Thread handoff — engine → UI
The engine thread never calls `Platform.runLater()`. Instead it writes
a `RenderSnapshot` to an `AtomicReference`. The UI thread reads this
reference on every `AnimationTimer` pulse. This is a **single-writer,
single-reader** pattern with no locking required.

```
Engine thread:                    UI thread (60 FPS):
  snapshot = buildSnapshot()        snap = snapshotRef.get()
  snapshotRef.set(snapshot)   ←→    canvas.paint(snap)
```

The `RenderSnapshot` is an **immutable value object**. Once written by
the engine, it is never mutated. The UI thread may hold a reference to
an old snapshot safely — it will be replaced on the next engine cycle,
not mutated in place.

---

## 3. Package structure

```
com.muralis/
├── Application.java              Entry point. Wires all modules. No logic.
│
├── model/                        Shared immutable value types (DATA-CONTRACTS.md)
│   ├── OrderBookSnapshot.java    
│   ├── OrderBookDelta.java       
│   ├── NormalizedTrade.java      
│   ├── InstrumentSpec.java       
│   ├── ConnectionEvent.java      
│   ├── AggressorSide.java        (enum)
│   └── MarketEvent.java          Sealed interface — union of all queue event types
│
├── provider/                     Provider SPI (SPEC-provider-spi.md)
│   ├── MarketDataProvider.java   Interface
│   ├── MarketDataListener.java   Interface
│   ├── ConnectionState.java      (enum)
│   └── ProviderType.java         (enum)
│
├── ingestion/                    Binance-specific adapter (SPEC-ingestion.md)
│   ├── BinanceAdapter.java       Implements MarketDataProvider
│   ├── BinanceWebSocketClient.java
│   ├── BinanceMessageParser.java
│   └── SnapshotFetcher.java      REST snapshot via HttpClient
│
├── engine/                       Order book and trade state (SPEC-engine.md)
│   ├── OrderBookEngine.java      Main engine thread loop
│   ├── OrderBook.java            Mutable bid/ask state (engine thread only)
│   ├── TradeBuffer.java          Ring buffer of recent trades (engine thread only)
│   └── RenderSnapshot.java       Immutable — written by engine, read by UI
│
└── ui/                           JavaFX rendering (SPEC-rendering.md)
    ├── MuralisApp.java           JavaFX Application subclass
    ├── LadderCanvas.java         Canvas + AnimationTimer
    ├── LadderPainter.java        All canvas draw calls
    ├── BubblePainter.java        Trade bubble rendering
    └── ColorScheme.java          All colors defined in one place
```

---

## 4. Dependency rules

These rules define which packages may import from which. Violations break
the architecture. Claude Code must check imports before finalising any class.

```
model/       →  (nothing) — model has zero dependencies on other Muralis packages
provider/    →  model/
ingestion/   →  model/, provider/
engine/      →  model/, provider/
ui/          →  model/, engine/ (RenderSnapshot only)

Application  →  all packages (it is the composition root — only class allowed to)
```

**Explicit forbidden imports:**

| Package | Must never import from |
|---|---|
| `model/` | Any other `com.muralis` package |
| `engine/` | `ingestion/` — engine is provider-agnostic |
| `engine/` | `ui/` — no JavaFX types in engine |
| `ingestion/` | `engine/` — adapter does not know about order books |
| `ingestion/` | `ui/` — no JavaFX types in adapter |
| `ui/` | `ingestion/` — UI does not know which provider is active |
| `ui/` | `provider/` — UI does not know about connection management |

**The only permitted cross-cutting dependency:**
`ui/` may import `engine.RenderSnapshot` — this is the single data
handoff point between engine and UI. It imports nothing else from `engine/`.

---

## 5. `MarketEvent` — the queue's type

The `LinkedTransferQueue` carries a sealed interface `MarketEvent`. This
allows the engine's event loop to switch on type safely using Java 21
pattern matching, with no casting and no `instanceof` chains.

```java
package com.muralis.model;

public sealed interface MarketEvent
    permits OrderBookSnapshot, OrderBookDelta, NormalizedTrade, ConnectionEvent {
}
```

Each event type in `DATA-CONTRACTS.md` Section 3 implements `MarketEvent`.
The engine's consume loop uses:

```java
switch (event) {
    case OrderBookSnapshot s -> engine.applySnapshot(s);
    case OrderBookDelta    d -> engine.applyDelta(d);
    case NormalizedTrade   t -> engine.applyTrade(t);
    case ConnectionEvent   c -> engine.applyConnectionEvent(c);
}
```

No `default` branch. The compiler enforces exhaustiveness via the sealed
interface. If a new event type is added to `MarketEvent`, the compiler
will flag every unhandled switch — making omissions impossible to miss.

---

## 6. `RenderSnapshot` — the engine-to-UI handoff type

`RenderSnapshot` is the only data structure the UI thread reads from the
engine. It is constructed by the engine at the end of each event
processing cycle and written atomically. The UI reads it on every frame.

```java
package com.muralis.engine;

public record RenderSnapshot(
    String         symbol,
    long           exchangeTs,       // Timestamp of the last applied event
    long[]         bidPrices,        // Fixed-point. Sorted descending.
    long[]         bidQtys,          // Parallel to bidPrices.
    long[]         askPrices,        // Fixed-point. Sorted ascending.
    long[]         askQtys,          // Parallel to askPrices.
    List<TradeBlip> recentTrades,    // Trades within the decay window
    ConnectionState connectionState, // For status indicator in UI
    InstrumentSpec  instrumentSpec   // For price formatting at render time
) {}
```

**`TradeBlip`** — a lightweight trade record for the bubble renderer:
```java
public record TradeBlip(
    long          price,         // Fixed-point
    long          qty,           // Fixed-point
    AggressorSide aggressorSide,
    long          exchangeTs     // Used by UI to compute bubble age and alpha
) {}
```

**Invariants:**
- `RenderSnapshot` is a Java `record` — immutable by construction
- `recentTrades` is an unmodifiable `List` — never expose the engine's
  mutable buffer
- `bidPrices` and `askPrices` are defensive copies of the engine's arrays
- The UI thread never modifies any field or collection in `RenderSnapshot`
- A `null` snapshot reference means the engine has not yet produced its
  first snapshot — the UI must render a "connecting..." state

---

## 7. `Application.java` — the composition root

`Application.java` is the only class permitted to instantiate and wire all
modules together. It contains no business logic. Its sole responsibility is
dependency wiring and lifecycle management.

```
Application.main()
  │
  ├── Create InstrumentSpec (BTCUSDT)
  ├── Create LinkedTransferQueue<MarketEvent>
  ├── Create AtomicReference<RenderSnapshot>
  ├── Create OrderBookEngine(queue, snapshotRef, instrumentSpec)
  ├── Create BinanceAdapter(queue, instrumentSpec)
  ├── Start engine thread ("muralis-engine")
  ├── Connect BinanceAdapter (triggers bootstrap sequence)
  └── Launch JavaFX Application (MuralisApp) with snapshotRef
```

**Rules for `Application.java`:**
- No `if` statements beyond null checks
- No business logic (no parsing, no state, no calculations)
- No static state beyond what JavaFX requires
- If the composition grows beyond ~40 lines, extract a `WiringContext`
  helper — but do not introduce a DI framework

---

## 8. Architecture Decision Records (ADRs)

ADRs are appended here when a locked decision in this file is revisited.
Each ADR must state: the decision being changed, the reason, the impact
on existing spec files, and the date.

*No ADRs before v1.2.*

### ADR-001: Binance Spot → Binance USDⓈ-M Futures
**Date:** 2026-03-29 | **Status:** Accepted

**Decision changed:** Data source switched from Binance Spot
(`stream.binance.com`) to Binance USDⓈ-M Futures
(`fstream.binance.com`).

**Reason:** Binance Spot returns HTTP 451 "Unavailable For Legal
Reasons" for all US IP addresses. VPN workaround is not acceptable.
Binance Futures is not geo-blocked in the US.

**Key differences from Spot:**
- Trade stream: `@aggTrade` (not `@trade`) — aggregate fills at
  same price/side within 100ms. Trade ID from `a` field, not `t`.
- Depth stream: adds `pu` (previous update ID) field — enables
  simpler gap detection. Adapter uses `pu` internally; model types
  unchanged.
- REST snapshot: `fapi.binance.com/fapi/v1/depth`, limit=1000
  (not 5000). Response body includes `E` (event time).
- InstrumentSpec BTCUSDT: `priceScale=1` (not 2), `qtyScale=3`
  (not 8), `tickSize=1L` (=0.1), `minQty=1L` (=0.001).
- Tick sizes are subject to change by Binance. Phase 2 should
  fetch from `/fapi/v1/exchangeInfo` at startup.

**Impact on spec files:** PROJECT.md, DATA-CONTRACTS.md,
SPEC-ingestion.md, SPEC-provider-spi.md updated. SPEC-engine.md,
SPEC-rendering.md, BUILD.md unchanged (architecture is
provider-agnostic).

**Full ADR:** See `ADR-001-binance-futures.md` in project root.

---

## 9. What this architecture explicitly defers

The following are known future concerns. They are not addressed in Phase 1
and must not influence Phase 1 implementation decisions.

| Concern | Deferred to |
|---|---|
| Multiple simultaneous instruments | Phase 2 — requires engine refactor |
| LMAX Disruptor queue replacement | Phase 2 — swap behind queue abstraction |
| Disk-backed event log (Chronicle Queue) | Phase 2 — for historical replay |
| CME/Rithmic adapter | Phase 2 — new `ingestion/` implementation |
| Multi-window or split-view UI | Phase 2 — JavaFX scene graph refactor |
| GraalVM native image packaging | Post-Phase 2 — after API stabilises |
| Fetch InstrumentSpec from exchange at startup | Phase 2 — hardcoded in Phase 1 |

---

*Last updated: ARCHITECTURE.md v1.2 — ADR-001 appended (Binance Spot → Futures).*
*Next file: SPEC-ingestion.md*
