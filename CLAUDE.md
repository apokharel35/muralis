# CLAUDE.md — Instructions for Claude Code

> This file is read automatically by Claude Code at the start of every
> session. It defines the project context, hard rules, and working
> patterns for all code generation in Muralis.

---

## What is Muralis

Muralis is a single-JVM desktop application that visualizes live order
book depth and trade flow from Binance USDⓈ-M Futures. It is built
with Java 21 and JavaFX 21. There is no server, no REST API, no
database, and no Spring Boot. It is a focused read-only visualization
tool.

**Data source:** Binance USDⓈ-M Futures (not Spot — Spot is
geo-blocked in the US). See ADR-001 in ARCHITECTURE.md Section 8.

---

## Spec files — read these before generating code

| File | Covers |
|---|---|
| `PROJECT.md` | Vision, Phase 1 scope, quality constraints, glossary |
| `DATA-CONTRACTS.md` | All shared types, enums, field definitions, parsing rules |
| `ARCHITECTURE.md` | Package structure, thread model, dependency rules, ADRs |
| `SPEC-ingestion.md` | BinanceAdapter, WebSocket bootstrap, reconnection, parsing |
| `SPEC-engine.md` | OrderBook, TradeBuffer, RenderSnapshot, engine thread loop |
| `SPEC-rendering.md` | JavaFX canvas, painters, AnimationTimer, controls, colors |
| `SPEC-provider-spi.md` | MarketDataProvider interface, ProviderConfig, discovery |
| `BUILD.md` | Gradle config, dependency coordinates, JVM flags |
| `DEV-PLAN.md` | Step-by-step implementation sequence with prompts |
| `ADR-001-binance-futures.md` | Full Spot-to-Futures migration analysis |
| `MIGRATION-SUMMARY.md` | Complete migration report with all findings and fixes |

---

## Hard rules — never violate these

### Types
- **No `double` or `float` for any price or quantity.** Use `long`
  fixed-point exclusively. See `DATA-CONTRACTS.md` Section 1.
- **No `BigDecimal` beyond the parse boundary.**
- **`longValueExact()` in all price/quantity parsing.**

### Threading
- **Three threads only.** Ingestion, engine (`muralis-engine`), UI.
- **No `Thread.sleep()` in engine or ingestion.**
- **No `Platform.runLater()` outside `com.muralis.ui`.**
- **No blocking I/O on the UI thread.**

### Dependencies
- **No Spring, no Jakarta EE, no Lombok, no Guava.**
- **No new dependencies** beyond `BUILD.md` Section 2.

### Architecture
- **Respect package dependency rules** — `ARCHITECTURE.md` Section 4.
- **`Application.java` is the only composition root.**
- **`ConnectionState` and `ConnectionEvent` live in `com.muralis.model`.**
- **`InstrumentSpec` does NOT implement `MarketEvent`.**

---

## Binance Futures specifics (ADR-001)

### URLs and streams
The WebSocket connects to THREE streams on a single connection:

```
wss://fstream.binance.com/stream?streams=btcusdt@depth20@100ms/btcusdt@depth@100ms/btcusdt@aggTrade
```

| Stream | Purpose |
|---|---|
| `@depth20@100ms` | Partial book snapshot (top 20 levels) — used for bootstrap only |
| `@depth@100ms` | Incremental depth diffs — live book maintenance |
| `@aggTrade` | Aggregate trade events |

### REST API — GEO-BLOCKED
`fapi.binance.com` returns HTTP 451 for US IPs. `SnapshotFetcher.java`
exists in the codebase but is NOT called. Bootstrap uses `@depth20`
WebSocket stream instead. See SPEC-ingestion.md Section 3.

### Bootstrap sequence (WebSocket-only)
1. Subscribe to all three streams
2. Ignore `@depth` diffs during bootstrap
3. Accept first `@depth20` message as snapshot (20 levels)
4. Set `awaitingFirstDiff = true`
5. Accept the very next `@depth` diff unconditionally (anchors chain)
6. All subsequent diffs validate via `pu`
7. Ignore `@depth20` after sync

**Why first diff is unconditional:** `@depth20` and `@depth` use
different update ID sequences that never align. `pu`-chain validation
between them always fails.

### Trade stream: `@aggTrade` (NOT `@trade`)
- Trade ID from `a` field (aggregate trade ID), NOT `t`
- Quantity from `q` field (aggregate quantity)
- Ignore fields: `nq`, `f`, `l`
- `m` (isBuyerMaker) field is the same as Spot

### Depth stream: has `pu` field
- `pu` = previous final update ID — used for gap detection
- Used **inside the adapter only** — NOT added to `OrderBookDelta`

### Gap detection (live diffs after sync)
```
if diff.finalUpdateId <= lastPublished → stale, discard silently
if diff.pu == lastPublished → valid, publish
if diff.pu != lastPublished → gap, reconnect
```

### InstrumentSpec — BTCUSDT Futures (Phase 1 default)
```
symbol      = "BTCUSDT"
priceScale  = 2          // prices have 2 decimal places (tick = 0.01)
tickSize    = 1L         // = 0.01 in display
qtyScale    = 3          // quantities have 3 decimal places
minQty      = 1L         // = 0.001 BTC
currency    = "USDT"
provider    = BINANCE_FUTURES
```

**WARNING:** Binance periodically changes tick sizes. The `priceScale`
was initially set to 1 based on stale data (tick was 0.10 from 2022),
but Binance reverted to 0.01 in June 2025. Always verify against
`GET /fapi/v1/exchangeInfo` before release.

---

## Package to spec file mapping

| Package | Spec file | Invariant checklist |
|---|---|---|
| `com.muralis.model` | `DATA-CONTRACTS.md` | Section 8 |
| `com.muralis.provider` | `SPEC-provider-spi.md` | Section 8 |
| `com.muralis.ingestion` | `SPEC-ingestion.md` | Section 10 |
| `com.muralis.engine` | `SPEC-engine.md` | Section 10 |
| `com.muralis.ui` | `SPEC-rendering.md` | Section 11 |

---

## Key design patterns

### Sealed interface for queue events
```java
public sealed interface MarketEvent
    permits OrderBookSnapshot, OrderBookDelta, NormalizedTrade, ConnectionEvent {}
```

### Fixed-point price parsing (adapter boundary only)
```java
long parsePrice(String raw) {
    return new BigDecimal(raw)
        .movePointRight(instrumentSpec.priceScale())
        .longValueExact();
}
```

### Engine to UI handoff (no locking)
```
Engine: snapshotRef.set(buildSnapshot())
UI:     snap = snapshotRef.get()
```

---

## Build and run commands

| Task | Command (Windows) |
|---|---|
| Build | `.\gradlew.bat build` |
| Run | `.\gradlew.bat run` |
| Test | `.\gradlew.bat test` |
| Dependencies | `.\gradlew.bat dependencies --configuration runtimeClasspath` |

---

## What NOT to do

- Do not refactor working code
- Do not generate ahead of the current DEV-PLAN step
- Do not add `module-info.java`
- Do not use `gc.clip()` in any canvas painter
- Do not create classes beyond what each spec file lists
- Do not use Spot URLs (`stream.binance.com`) — they are geo-blocked
- Do not use REST URL (`fapi.binance.com`) — also geo-blocked for US
- Do not add `pu` to `OrderBookDelta` — adapter uses it internally
- Do not use `t` field for trade ID — Futures uses `a` field
- Do not validate first diff's `pu` after @depth20 sync — it will
  never match (different update ID sequences)

---

## Sequence ID tracking

The adapter tracks: `lastPublishedFinalUpdateId` (its own field).
This is distinct from:
- `snapshot.lastUpdateId` — the @depth20 snapshot's `u` field
- `delta.firstUpdateId` / `delta.finalUpdateId` — the delta's range
- `delta.pu` — the previous delta's `u` (read from JSON, not stored)

After @depth20 sync, the first diff is accepted unconditionally and
anchors the chain at its `finalUpdateId`. The snapshot's `u` value
is NOT part of the diff chain.

---

## Current phase

**Phase 1 in progress.** Follow `DEV-PLAN.md` step sequence.
Code written through Step 6. Futures migration complete and verified.
Next: Step 7 (status bar, controls, theme toggle).
