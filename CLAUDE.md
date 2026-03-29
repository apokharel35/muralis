# CLAUDE.md — Instructions for Claude Code

> This file is read automatically by Claude Code at the start of every
> session. It defines the project context, hard rules, and working
> patterns for all code generation in Muralis.

---

## What is Muralis

Muralis is a single-JVM desktop application that visualizes live order
book depth and trade flow from Binance. It is built with Java 21 and
JavaFX 21. There is no server, no REST API, no database, and no
Spring Boot. It is a focused read-only visualization tool.

---

## Spec files — read these before generating code

The project root contains spec files that are the **sole source of
truth** for all implementation decisions. Before generating any class,
read the relevant spec files. Do not infer types, field names, or
behaviour from general knowledge — use the specs.

| File | Covers |
|---|---|
| `PROJECT.md` | Vision, Phase 1 scope, quality constraints, glossary |
| `DATA-CONTRACTS.md` | All shared types, enums, field definitions, parsing rules |
| `ARCHITECTURE.md` | Package structure, thread model, dependency rules |
| `SPEC-ingestion.md` | BinanceAdapter, WebSocket bootstrap, reconnection, parsing |
| `SPEC-engine.md` | OrderBook, TradeBuffer, RenderSnapshot, engine thread loop |
| `SPEC-rendering.md` | JavaFX canvas, painters, AnimationTimer, controls, colors |
| `SPEC-provider-spi.md` | MarketDataProvider interface, ProviderConfig, discovery |
| `BUILD.md` | Gradle config, dependency coordinates, JVM flags |
| `DEV-PLAN.md` | Step-by-step implementation sequence with prompts |
| `manual.md` | Plain-language decision explanations and developer guide |

---

## Hard rules — never violate these

These are non-negotiable. Every generated class must satisfy all of them.
If a rule conflicts with what seems "better," the rule wins.

### Types
- **No `double` or `float` for any price or quantity.** Use `long`
  fixed-point exclusively. See `DATA-CONTRACTS.md` Section 1.
- **No `BigDecimal` beyond the parse boundary.** `BigDecimal` is
  permitted only inside `BinanceMessageParser` for JSON-to-long
  conversion, and inside UI formatters for long-to-display conversion.
  It must never appear in engine fields, method signatures, or queued events.
- **`longValueExact()` in all price/quantity parsing.** Never
  `longValue()` — precision loss must throw, not silently corrupt.

### Threading
- **Three threads only.** Ingestion (WebSocket library), engine
  (`muralis-engine`), UI (JavaFX Application Thread). No thread pools,
  no virtual threads, no `ExecutorService`, no additional threads.
- **No `Thread.sleep()` in engine or ingestion.** Use `queue.poll(100, MILLISECONDS)`
  for the engine loop. Reconnection backoff in the adapter uses scheduled
  delays on the ingestion thread only.
- **No `Platform.runLater()` outside `com.muralis.ui`.** The engine
  writes to `AtomicReference<RenderSnapshot>`. The UI reads it in
  `AnimationTimer`. No cross-thread UI calls.
- **No blocking I/O on the UI thread.** No network, no disk, no
  `queue.poll()`, no `Thread.sleep()`.

### Dependencies
- **No Spring, no Jakarta EE, no Lombok, no Guava.** The only
  permitted dependencies are listed in `BUILD.md` Section 2.
  Do not add, substitute, or upgrade any dependency.
- **No new dependencies.** If a task seems to require a library not
  in `BUILD.md`, stop and ask. The answer is almost always "use the
  JDK standard library."

### Architecture
- **Respect package dependency rules.** Before writing any import,
  check `ARCHITECTURE.md` Section 4. Key restrictions:
  - `model/` imports nothing from other `com.muralis` packages
  - `engine/` never imports from `ingestion/` or `ui/`
  - `ingestion/` never imports from `engine/` or `ui/`
  - `ui/` never imports from `ingestion/` or `provider/`
  - `ui/` may import `engine.RenderSnapshot`, `engine.TradeBlip`,
    and `engine.RenderConfig` only — nothing else from `engine/`
- **`Application.java` is the only composition root.** It is the
  only class that may import from all packages. It contains zero
  business logic — only wiring.
- **`ConnectionState` and `ConnectionEvent` live in `com.muralis.model`.**
  Not in `provider/`. This is required so `MarketEvent` sealed
  interface works without cross-package dependency.
- **`InstrumentSpec` does NOT implement `MarketEvent`.** It is a
  configuration record, not a queue event.

---

## Package → spec file mapping

When generating a class, read the spec file for its package:

| Package | Spec file | Invariant checklist |
|---|---|---|
| `com.muralis.model` | `DATA-CONTRACTS.md` | Section 8 |
| `com.muralis.provider` | `SPEC-provider-spi.md` | Section 8 |
| `com.muralis.ingestion` | `SPEC-ingestion.md` | Section 10 |
| `com.muralis.engine` | `SPEC-engine.md` | Section 10 |
| `com.muralis.ui` | `SPEC-rendering.md` | Section 11 |
| `com.muralis.Application` | `ARCHITECTURE.md` | Section 7 |
| `build.gradle.kts` | `BUILD.md` | Section 4 |

**Always verify against the invariant checklist before considering
any class complete.**

---

## Key design patterns to follow

### Sealed interface for queue events
```java
// com.muralis.model.MarketEvent
public sealed interface MarketEvent
    permits OrderBookSnapshot, OrderBookDelta, NormalizedTrade, ConnectionEvent {}
```
Engine switch uses pattern matching with no `default` branch.

### Fixed-point price parsing (adapter boundary only)
```java
long parsePrice(String raw) {
    return new BigDecimal(raw)
        .movePointRight(instrumentSpec.priceScale())
        .longValueExact();
}
```

### Engine → UI handoff (no locking, no Platform.runLater)
```
Engine thread:  snapshotRef.set(buildSnapshot())
UI thread:      snap = snapshotRef.get()   // every AnimationTimer frame
```
`RenderSnapshot` is an immutable record. Defensive copies of all arrays.

### Bubble decay uses `receivedTs`, not `exchangeTs`
`TradeBlip` carries both timestamps. All decay/drift/alpha calculations
in `BubblePainter` use `blip.receivedTs()` to avoid clock skew between
the exchange and local machine.

### Shutdown via callback (UI never imports adapter)
```java
// In Application.main():
MuralisApp.shutdownCallback = () -> adapter.disconnect();
// In MuralisApp.stop():
shutdownCallback.run();
```

---

## Build and run commands

Always use the Gradle wrapper, never the global `gradle` command.

| Task | Command (Windows) |
|---|---|
| Build | `.\gradlew.bat build` |
| Run | `.\gradlew.bat run` |
| Test | `.\gradlew.bat test` |
| Dependencies | `.\gradlew.bat dependencies --configuration runtimeClasspath` |
| Clean | `.\gradlew.bat clean` |

The `run` task applies all JVM flags from `BUILD.md` Section 4,
including ZGC, JavaFX pipeline config, and heap limits.

---

## What NOT to do

- **Do not refactor working code.** If a step passes its verification,
  move on. Do not "improve" or "clean up."
- **Do not generate ahead.** Each step in `DEV-PLAN.md` builds on the
  previous one. Do not generate classes from a later step.
- **Do not add `module-info.java`.** JPMS is intentionally excluded
  in Phase 1. See `BUILD.md` Section 5.
- **Do not use `gc.clip()` in any canvas painter.** It allocates a
  texture per call. The bubble panel is a separate Canvas — drawing
  is naturally bounded.
- **Do not create new top-level classes** beyond what each spec file
  lists. Helpers are private methods or private inner records.
- **Do not use `double` arithmetic for bar width or bubble sizing
  with price/qty values.** Convert from `long` to `double` only at
  the final render calculation, never store the result back.
- **Do not invent field names.** Every field name in model, engine,
  and ingestion classes is defined in `DATA-CONTRACTS.md`. Use those
  exact names.

---

## Sequence ID tracking — naming convention

The adapter tracks the last delta it published to the queue in a field
called `lastPublishedFinalUpdateId`. This is distinct from:
- `snapshot.lastUpdateId` — the REST snapshot's sequence ID
- `delta.firstUpdateId` / `delta.finalUpdateId` — the delta's range

Do not use `lastAppliedFinalUpdateId` or `lastUpdateId` as the
adapter's tracking field name. The word "published" clarifies that
the adapter (not the engine) owns this state.

---

## Order of operations for each coding task

1. Read the spec file(s) named in the prompt
2. Identify the package and its dependency rules
3. Generate the code
4. Check every import against `ARCHITECTURE.md` Section 4
5. Check every price/qty field is `long`, not `double`
6. Check the invariant checklist for that package's spec file
7. Confirm no forbidden patterns (sleep, Platform.runLater, etc.)

---

## Current phase

**Phase 1 in progress.** Follow `DEV-PLAN.md` step sequence exactly.
No step begins until the previous one is verified working.

Default instrument: `BTCUSDT` (priceScale=2, qtyScale=8, tickSize=1L).
See `DATA-CONTRACTS.md` Section 5.1 for full `InstrumentSpec` values.
