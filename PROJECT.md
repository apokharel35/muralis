# PROJECT.md — Muralis

> "Beyond the wall." Muralis is a lightweight, desktop-first order flow
> visualization tool for serious traders. It shows what is happening inside
> the market microstructure in real time, with no unnecessary weight.

---

## 1. Vision

Muralis gives traders a clean, high-signal window into live market depth and
order flow. It is not a charting platform. It is not a broker terminal. It is
a focused visualization tool that renders the live DOM and trade flow with
minimal latency, minimal memory, and zero UI clutter.

The competitive target is the niche occupied by Bookmap and Sierra Chart's
order flow views — but Muralis is intentionally lighter, faster to start, and
built for a single focused purpose.

---

## 2. Phase 1 scope (MVP — target: 5 days)

Phase 1 ships exactly two visual components, live from a Binance WebSocket
feed, with correct order book state management and reconnection handling.

### 2.1 DOM Ladder
A vertically scrolling price ladder showing:
- Live bid quantity at each price level (left side)
- Live ask quantity at each price level (right side)
- Current best bid and best ask highlighted
- Auto-centering on the current mid-price, with mouse-wheel override

### 2.2 Trade Bubbles
Circles rendered on the ladder at the traded price level where:
- Circle diameter is proportional to trade quantity (capped at a max display size)
- Aggressive buy trades render in green
- Aggressive sell trades render in red
- Bubbles fade out over a configurable decay window (default: 3 seconds)
- Aggressor direction is derived exclusively from the exchange-provided
  `isBuyerMaker` field — never inferred locally

### 2.3 Data source (Phase 1)
- Binance USDⓈ-M Futures WebSocket (`wss://fstream.binance.com`)
- Streams: `<symbol>@depth@100ms` + `<symbol>@aggTrade`
- REST snapshot: `https://fapi.binance.com/fapi/v1/depth`
- Default instrument for development: `BTCUSDT` (perpetual contract)
- Single instrument per session for MVP
- See ADR-001 in ARCHITECTURE.md Section 8 for migration rationale

### 2.4 Rendering target
- JavaFX Canvas via `AnimationTimer` at 60 FPS
- Single window, single canvas
- Minimum supported resolution: 1920×1080

---

## 3. Phase 2 scope (post-MVP — not scheduled)

The following are confirmed Phase 2 features. They must not influence any
Phase 1 architectural decision unless a seam is explicitly called out.

- **Footprint chart** — per-price-level volume accumulation per time candle,
  with buy volume, sell volume, and delta columns
- **Delta coloring** — green/red intensity per price level based on
  buy vol minus sell vol within the current candle
- **Historical TPO / Market Profile** — time-price opportunity visualization
  over historical session data
- **Historical playback** — replay recorded tick data at configurable speed
- **Second instrument** — multi-tab or split-view support for a second symbol

---

## 4. Explicit non-goals (never, or not before Phase 3)

These are hard boundaries. No spec file, no generated code, and no
architectural decision should be made in anticipation of these features.

- **Order entry or trading** — Muralis never sends orders. Read-only only.
- **Heatmap (time × price × volume)** — a distinct visualization type
  with different rendering and storage requirements; deferred indefinitely
- **Alerts or notifications** — no price alerts, no push notifications
- **Cloud sync or remote data** — local-first only; no cloud backend
- **Multi-monitor spanning** — single window for Phase 1 and 2
- **Web or mobile interface** — desktop JVM only
- **Broker integration** — no position display, no P&L, no account data
- **Plugin marketplace** — no third-party plugin system in Phase 1 or 2

---

## 5. Quality constraints (non-negotiable for all phases)

These are architectural invariants, not aspirational goals. Every spec file
must respect them. Every generated implementation must be verified against them.

| Constraint | Target |
|---|---|
| Application startup time | < 2 seconds to first rendered frame |
| Steady-state heap (1 instrument) | < 512 MB |
| Peak heap (1 instrument, 1 hr session) | < 1 GB |
| UI frame rate (steady state) | 60 FPS ± 5 |
| UI freeze on network event | Never — zero blocking calls on UI thread |
| WebSocket reconnection | Automatic, within 30 seconds, with full book resync |
| Price representation | `long` fixed-point throughout — never `double` or `float` |

---

## 6. Technology decisions (locked)

These decisions are final. They must not be revisited in any spec file or
code generation prompt without an explicit ADR (Architecture Decision Record)
added to this project.

| Decision | Choice | Rationale |
|---|---|---|
| Language | Java 21 | Developer proficiency; LTS; virtual threads available |
| Build tool | Gradle 8.x (Kotlin DSL) | Type-safe, modern, strong Claude Code support |
| UI framework | JavaFX 21 (Canvas API) | GPU-accelerated 2D; sufficient for Phase 1 at 60 FPS |
| Dependency injection | None — manual constructor injection | Zero overhead; ~15 classes at MVP scale |
| WebSocket client | Java-WebSocket 1.6.0 | Zero transitive deps; NIO-based; 100 KB JAR |
| JSON parsing | Gson 2.11.0 | Zero transitive deps; adequate for flat Binance JSON |
| Logging | Logback 1.5.x + SLF4J | Industry standard; zero Spring dependency |
| Spring Boot | **Excluded** | Autoconfiguration, embedded Tomcat, and classpath scanning add ~400 MB RSS and 2+ sec startup for zero benefit in a desktop app |

---

## 7. Glossary

All spec files use these terms with exactly these definitions. No synonyms.
No loose usage.

| Term | Definition |
|---|---|
| **Instrument** | A tradeable symbol, e.g. `BTCUSDT`. Carries an `InstrumentSpec` defining tick size, price scale, and quantity scale. |
| **Price level** | A single price point in the order book, represented as a `long` in fixed-point notation. |
| **Tick** | The minimum price increment for an instrument. Stored as a `long` in fixed-point. |
| **Order book** | The current live state of all resting bids and asks, maintained in memory from a snapshot + delta stream. |
| **Snapshot** | A complete order book state delivered via REST at connection time. The starting point for local book maintenance. |
| **Delta** | An incremental update to the order book. A quantity of `0` means the price level must be removed. |
| **Sequence ID** | A monotonically increasing integer (`long`) assigned by the exchange to each delta. Used to detect gaps. |
| **Gap** | A missing sequence ID between two consecutive deltas. Requires full book reconstruction. |
| **Trade** | A single matched aggressive order. Carries price, quantity, direction, exchange timestamp, and trade ID. |
| **Aggressive side** | The side that crossed the spread to fill. Determined by `isBuyerMaker`: `false` = buyer aggressed (lifted offer); `true` = seller aggressed (hit bid). |
| **Trade bubble** | A circle rendered on the DOM ladder at the traded price level. Size encodes quantity. Color encodes aggressive side. |
| **DOM Ladder** | The price ladder visualization showing live resting bid/ask quantities per price level. |
| **Exchange timestamp** | The timestamp assigned by the exchange, carried in every event. **Always preferred over local system time** for all candle and display logic. |
| **Local timestamp** | `System.currentTimeMillis()` at the moment of message receipt. Used only for latency diagnostics — never for candle or display logic. |
| **Provider** | An implementation of `MarketDataProvider` that connects to a specific exchange or data feed. |
| **Adapter** | Synonym for Provider. Prefer "Provider" in spec files, "Adapter" in class names. |
| **Session** | A single run of the application from start to shutdown. No state persists across sessions in Phase 1. |
| **Fixed-point price** | A price stored as a `long` scaled by `10^priceScale`. E.g. price `97432.51` with `priceScale=2` is stored as `9743251L`. Never use `double` or `float` for prices. |
| **UI thread** | The JavaFX Application Thread. **No blocking operations, no I/O, no parsing may occur on this thread.** |
| **Ingestion thread** | The thread owned by the WebSocket client. Responsible for parsing and publishing events to the queue. No UI calls. |
| **Engine thread** | A dedicated single thread that consumes from the queue and maintains order book state. No UI calls. |

---

## 8. File map (living — updated as specs are added)

```
muralis/
├── PROJECT.md              ← this file
├── DATA-CONTRACTS.md       ← all shared types, enums, and invariants
├── ARCHITECTURE.md         ← module map, thread model, dependency rules
├── SPEC-ingestion.md       ← BinanceAdapter, WebSocket bootstrap, reconnection
├── SPEC-engine.md          ← OrderBook, FootprintEngine (Phase 2 seam)
├── SPEC-rendering.md       ← JavaFX canvas, AnimationTimer, bubble + ladder
├── SPEC-provider-spi.md    ← MarketDataProvider interface + ServiceLoader
├── BUILD.md                ← Gradle setup, dependencies, run configuration
└── src/
    └── main/
        └── java/
            └── com/muralis/
                ├── Application.java
                ├── provider/
                ├── ingestion/
                ├── engine/
                └── ui/
```

---

*Last updated: PROJECT.md v1.2 — Data source changed from Binance Spot to USDⓈ-M Futures (ADR-001).*
*Next file: DATA-CONTRACTS.md*
