# DEV-PLAN.md — Muralis Incremental Development Plan

> This document defines the step-by-step implementation sequence for
> Muralis Phase 1. Each step is small enough to understand completely,
> test manually, and verify before moving forward. No step begins until
> the previous one is confirmed working.
>
> The goal is not speed — it is confidence. A working system built
> incrementally is worth more than a fast system with hidden bugs.

---

## How to use this document

Each step has:
- **What gets built** — exactly what code will exist after this step
- **What you will see** — how to verify it worked (no guessing)
- **What can go wrong** — the most likely failure and how to diagnose it
- **The Claude Code prompt** — copy-paste this exactly

Read the entire step before running the prompt. Understand what Claude
is about to do. After Claude generates the code, read it. You do not
need to understand every line — but you should understand what each
class is responsible for.

---

## Before you start — one-time setup

### 1. Create the project folder
```bash
mkdir muralis
cd muralis
```

### 2. Copy all spec files into the root
```
muralis/
├── PROJECT.md
├── DATA-CONTRACTS.md
├── ARCHITECTURE.md
├── SPEC-ingestion.md
├── SPEC-engine.md
├── SPEC-rendering.md
├── SPEC-provider-spi.md
├── BUILD.md
├── DECISIONS.md
└── DEV-PLAN.md     ← this file
```

### 3. Open Claude Code
```bash
cd muralis
claude   # or however you launch Claude Code in your environment
```

Claude Code reads the files in your current directory. Having all spec
files present means Claude has full context in every session.

---

## Step 0 — Project scaffold and build verification

**Objective:** Get a compiling, runnable project with a blank JavaFX
window before writing any business logic.

**What gets built:**
- `build.gradle.kts` and `settings.gradle.kts`
- `src/main/resources/logback.xml`
- `src/main/resources/META-INF/services/com.muralis.provider.MarketDataProvider`
- `Application.java` — stub with `main()` that launches JavaFX
- `MuralisApp.java` — stub that opens a blank 1100×800 dark window
- Gradle wrapper files

**What you will see:**
A dark window titled "Muralis — BTCUSDT" opens and stays open.
Nothing else happens. No errors in the console.

**What can go wrong:**
- JavaFX not found → ensure you are running JDK 21, not JRE
- `prism.order=es2,sw` warning on some Linux machines → ignore it,
  software fallback is fine for development
- Gradle download takes a while on first run → normal

### Claude Code prompt — Step 0:
```
Read BUILD.md, PROJECT.md, and SPEC-rendering.md.

Generate the following files exactly as specified in BUILD.md:
1. settings.gradle.kts (Section 3)
2. build.gradle.kts (Section 4) — include all JVM flags from Section 11
3. src/main/resources/logback.xml (Section 7)
4. src/main/resources/META-INF/services/com.muralis.provider.MarketDataProvider
   (SPEC-provider-spi.md Section 9 — contents: com.muralis.ingestion.BinanceAdapter)

Then generate two stub classes:
5. src/main/java/com/muralis/Application.java
   - Contains only: public static void main(String[] args) that calls
     Application.launch(MuralisApp.class, args)
   - No other logic yet

6. src/main/java/com/muralis/ui/MuralisApp.java
   - Extends javafx.application.Application
   - start(Stage stage): opens a 1100x800 window titled "Muralis — BTCUSDT"
   - Sets scene background to Color.web("#0d0d0f") (dark theme)
   - No canvas, no controls yet — just the window

Do not generate any other classes. The goal is a compiling project with
a blank window. Verify the build compiles by checking all imports exist.
```

**After this step:** Run `./gradlew run`. Confirm the dark window opens.

---

## Step 1 — Data model (all shared types)

**Objective:** Generate every value type and enum in `com.muralis.model`
and `com.muralis.provider`. These are pure data classes — no logic, no
threads, no network.

**What gets built:**
- `AggressorSide.java` (enum — in `model/`)
- `ConnectionState.java` (enum — in `model/`)
- `MarketEvent.java` (sealed interface — in `model/`)
- `OrderBookSnapshot.java` (record implementing MarketEvent — in `model/`)
- `OrderBookDelta.java` (record implementing MarketEvent — in `model/`)
- `NormalizedTrade.java` (record implementing MarketEvent — in `model/`)
- `ConnectionEvent.java` (record implementing MarketEvent — in `model/`)
- `InstrumentSpec.java` (record — in `model/` — does NOT implement MarketEvent)
- `ProviderType.java` (enum — in `provider/`)

**What you will see:**
`./gradlew build` passes with zero errors. No runtime behaviour yet.

**What can go wrong:**
- `sealed interface` permits clause doesn't match class names exactly
  → compiler error with a clear message — fix the class name or permits list
- Record component order matters for the compact constructor
  → follow DATA-CONTRACTS.md Section 3 field order exactly

### Claude Code prompt — Step 1:
```
Read DATA-CONTRACTS.md and ARCHITECTURE.md Section 3 (package structure).

Generate all classes in com.muralis.model and com.muralis.provider
exactly as specified in DATA-CONTRACTS.md Sections 2, 3, and 4.

Package placement:
- com.muralis.model: AggressorSide, ConnectionState, MarketEvent,
  OrderBookSnapshot, OrderBookDelta, NormalizedTrade, ConnectionEvent,
  InstrumentSpec
- com.muralis.provider: ProviderType

Rules:
- All event types (OrderBookSnapshot, OrderBookDelta, NormalizedTrade,
  ConnectionEvent) must implement the MarketEvent sealed interface
- InstrumentSpec does NOT implement MarketEvent — it is a configuration
  record, not a queue event
- ConnectionState and ConnectionEvent live in com.muralis.model (not
  provider) so the MarketEvent sealed permits clause works without
  cross-package dependency
- All classes are records or enums — no mutable state, no setters
- No business logic in any of these classes
- Follow the field names from DATA-CONTRACTS.md exactly —
  do not rename fields or change types

After generating, verify:
- MarketEvent sealed interface permits exactly:
  OrderBookSnapshot, OrderBookDelta, NormalizedTrade, ConnectionEvent
- All four permitted types are in com.muralis.model (same package)
- InstrumentSpec is NOT in the permits list
- All price and quantity fields are long — not double, not BigDecimal
- All timestamp fields are named exchangeTs or receivedTs and are long
- AggressorSide has exactly two values: BUY and SELL
- ConnectionState has exactly four values: CONNECTING, CONNECTED,
  RECONNECTING, DISCONNECTED

Do not generate any ingestion, engine, or UI classes yet.
```

**After this step:** Run `./gradlew build`. Zero errors expected.

---

## Step 2 — Provider interface

**Objective:** Generate the `MarketDataProvider` and `MarketDataListener`
interfaces. These are pure contracts — no implementation yet.

**What gets built:**
- `MarketDataProvider.java` (interface)
- `MarketDataListener.java` (interface)
- `ProviderConfig.java` (record)

**What you will see:**
`./gradlew build` still passes. Still no runtime behaviour.

### Claude Code prompt — Step 2:
```
Read SPEC-provider-spi.md Sections 2, 3, and 4.
Read DATA-CONTRACTS.md to understand the types these interfaces reference.

Generate:
1. com.muralis.provider.MarketDataProvider — interface as specified in
   SPEC-provider-spi.md Section 2, including all Javadoc comments
2. com.muralis.provider.MarketDataListener — interface as specified in
   SPEC-provider-spi.md Section 3
3. com.muralis.provider.ProviderConfig — record as specified in
   SPEC-provider-spi.md Section 4

Rules:
- These are interfaces and a record only — no implementation classes
- MarketDataProvider must declare getConnectionState() as thread-safe
  (volatile backing field is the implementor's responsibility)
- ProviderConfig.defaultFor() is a static factory method on the record

Do not generate BinanceAdapter or any implementation yet.
```

**After this step:** Run `./gradlew build`. Zero errors expected.

---

## Step 3 — Order book engine (no networking)

**Objective:** Build the engine layer — `OrderBook`, `TradeBuffer`,
`RenderConfig`, `RenderSnapshot`, and `TradeBlip`. These are pure
in-memory data structures with no network dependency.

**What gets built:**
- `OrderBook.java`
- `TradeBuffer.java`
- `RenderConfig.java`
- `RenderSnapshot.java` (record)
- `TradeBlip.java` (record)
- `OrderBookEngine.java` — the thread loop (stub — not started yet)

**What you will see:**
`./gradlew build` passes. Write your first unit test manually:
create a test that applies a snapshot to `OrderBook`, then applies a
delta that removes one level, and checks the result. If the test passes,
the order book logic is correct.

**What can go wrong:**
- `TreeMap` comparator for bids — must be `Comparator.reverseOrder()`
  so that `bids.firstKey()` gives the best (highest) bid
- Zero-quantity delta routing — `qty == 0L` must call `remove()`,
  never `put()`. The assertion in `OrderBook` will catch this.

### Claude Code prompt — Step 3:
```
Read SPEC-engine.md in full.
Read DATA-CONTRACTS.md Sections 1 and 3 for type constraints.
Read ARCHITECTURE.md Section 4 for import rules.

Generate the following classes in com.muralis.engine:

1. TradeBlip.java — record as specified in SPEC-engine.md Section 5
   - Must include receivedTs field (used for bubble decay, not exchangeTs)
2. RenderSnapshot.java — record as specified in SPEC-engine.md Section 5
3. RenderConfig.java — class as specified in SPEC-engine.md Section 6
   - bubbleDecayMs field must be volatile
4. OrderBook.java — class as specified in SPEC-engine.md Section 3
   - bids: TreeMap with Comparator.reverseOrder()
   - asks: TreeMap with natural order
   - setBid/setAsk must assert qty > 0L
   - removeBid/removeAsk on absent price is a no-op
5. TradeBuffer.java — class as specified in SPEC-engine.md Section 4
   - MAX_BLIPS = 500
   - Uses ArrayDeque + HashSet<Long> for seenTradeIds
6. OrderBookEngine.java — class as specified in SPEC-engine.md Section 2
   - Implement the full runLoop(), applySnapshot(), applyDelta(),
     applyTrade(), applyConnectionEvent(), and buildSnapshot() methods
   - Thread named "muralis-engine", not a daemon thread
   - Poll timeout: 100ms

Verify against SPEC-engine.md Section 10 invariant checklist before
completing. In particular:
- No JavaFX import anywhere in com.muralis.engine
- No com.muralis.ingestion import anywhere in com.muralis.engine
- RenderSnapshot arrays are defensive copies
- qty == 0L in applyDelta routes to remove, never to set
```

**After this step:** Write and run this manual test in your head:
- Apply a snapshot with 3 bid levels: [100, 99, 98]
- Apply a delta removing level 99 (qty = 0)
- `orderBook.bidDepth()` should return 2
- `orderBook.bestBid()` should return 100

---

## Step 4 — Binance adapter (network, no UI)

**Objective:** Connect to Binance, receive live data, and print parsed
events to the console. This is the first step with network activity.

**What gets built:**
- `BinanceMessageParser.java`
- `SnapshotFetcher.java`
- `BinanceWebSocketClient.java`
- `BinanceAdapter.java`

**What you will see:**
Console output like:
```
14:23:01.445 [muralis-engine] INFO  c.m.ingestion.BinanceAdapter — [BTCUSDT] WebSocket connected
14:23:01.891 [muralis-engine] INFO  c.m.ingestion.BinanceAdapter — [BTCUSDT] Order book synced. lastUpdateId=48291044
14:23:02.001 [muralis-engine] WARN  c.m.engine.OrderBookEngine — Best bid: 9743251, Best ask: 9743300
```

**What can go wrong:**
- Snapshot stale on first attempt — normal, adapter retries automatically
- `longValueExact()` throws on first parse → Binance sent a price with
  more decimal places than `priceScale` expects → check your
  `InstrumentSpec.priceScale` value in `DATA-CONTRACTS.md` Section 5.1
- WebSocket connection refused → check your internet connection and
  that `wss://stream.binance.com:9443` is not blocked by a firewall

### Claude Code prompt — Step 4:
```
Read SPEC-ingestion.md in full.
Read DATA-CONTRACTS.md Sections 1, 3, 5, and 7 (parsing boundary rules).
Read ARCHITECTURE.md Section 4 for import rules.

Generate the following classes in com.muralis.ingestion:

1. BinanceMessageParser.java — as specified in SPEC-ingestion.md Section 8
   - parsePrice() and parseQty() must use BigDecimal.movePointRight().longValueExact()
   - parseTrade() derives AggressorSide from isBuyerMaker field exactly
     as specified in DATA-CONTRACTS.md Section 2.2
   - exchangeTs for trades comes from field "T" not "E"

2. SnapshotFetcher.java — as specified in SPEC-ingestion.md Section 9
   - Uses java.net.http.HttpClient — no OkHttp, no Apache HttpClient
   - 10 second connect and read timeout
   - Throws SnapshotFetchException (define this as a checked exception
     in the same package) on any non-200 response or timeout

3. BinanceWebSocketClient.java — as specified in SPEC-ingestion.md Section 7
   - Extends org.java_websocket.client.WebSocketClient
   - Contains zero business logic — pure delegation to BinanceAdapter
   - Routes messages by "stream" field in the envelope JSON

4. BinanceAdapter.java — as specified in SPEC-ingestion.md Sections 6
   and the bootstrap sequence in Section 3 exactly
   - Pre-buffer uses ConcurrentLinkedQueue<OrderBookDelta>
   - Bootstrap sequence follows all 9 steps in Section 3.1
   - Gap detection follows Section 4.1 exactly — the tracking field is
     named lastPublishedFinalUpdateId (not lastUpdateId or lastApplied)
   - Reconnection backoff follows Section 5.2 exactly
   - disconnectTs field is declared even though unused (Phase 2 placeholder)
   - connect() must validate that config.symbol() matches
     instrumentSpec.symbol() — throw IllegalArgumentException if not

Then update Application.java to:
- Create InstrumentSpec for BTCUSDT (from DATA-CONTRACTS.md Section 5.1)
- Create LinkedTransferQueue<MarketEvent>
- Create AtomicReference<RenderSnapshot> initialised to null
- Create RenderConfig with default 5000ms decay
- Create OrderBookEngine and start it
- Create BinanceAdapter, add engine as listener, call connect()
- Set MuralisApp.shutdownCallback = () -> adapter.disconnect()
- Add a JVM shutdown hook that also calls adapter.disconnect()
  (safety net in case JavaFX stop() is not called)
- Keep MuralisApp launch commented out for now

After the engine processes its first snapshot, add a temporary debug
log in OrderBookEngine.applySnapshot() that prints bestBid and bestAsk
so we can confirm the book is populated.

Verify against SPEC-ingestion.md Section 10 logging specification —
all log messages must match the format specified there.
```

**After this step:** Run `./gradlew run`. Watch the console. You should
see the sync message within 3 seconds. The window will be blank (dark).
Let it run for 30 seconds and confirm no errors appear.

---

## Step 5 — Live ladder rendering (no bubbles yet)

**Objective:** Draw the DOM ladder live on the JavaFX canvas from the
`RenderSnapshot`. No bubbles yet — just the price ladder updating in
real time.

**What gets built:**
- `ColorScheme.java` — both DARK and LIGHT themes
- `LadderPainter.java` — full implementation
- `LadderCanvas.java` — AnimationTimer, zoom, scroll
- Updated `MuralisApp.java` — adds canvas to the scene

**What you will see:**
A live price ladder with green bid bars on the left, red ask bars on
the right, prices in the centre, and best bid/ask rows highlighted.
The ladder updates as the order book changes. Mouse wheel zooms in/out.

**What can go wrong:**
- Canvas appears black → `snapshotRef.get()` returns null longer than
  expected → add a "Connecting..." text render for the null case
- Bars not visible → check `maxBidQty` is not zero (empty book) and
  bar width calculation uses floating-point division not integer
- Ladder not centred → `bestBid()` returns -1L (empty book state
  during bootstrap) → render "Syncing..." text when book is empty

### Claude Code prompt — Step 5:
```
Read SPEC-rendering.md in full.
Read ARCHITECTURE.md Section 4 for import rules.
Read DATA-CONTRACTS.md Section 5.1 for price scale (BTCUSDT priceScale=2).

Generate the following classes in com.muralis.ui:

1. ColorScheme.java — as specified in SPEC-rendering.md Section 3
   - Both DARK and LIGHT static instances with all colors from Sections
     3.1 and 3.2 exactly

2. LadderPainter.java — as specified in SPEC-rendering.md Section 5
   - Paint order must follow Section 5.3 exactly (10 steps in order)
   - Bar normalisation is per-frame, per-side (Section 5.5)
   - Price formatting uses BigDecimal.valueOf(unscaled, scale) (Section 5.6)
   - Qty text only painted when rowHeightPx >= 14.0 (Section 5.7)
   - If snapshotRef returns null or book is empty: paint centred
     "Connecting..." text in scheme.priceText color

3. LadderCanvas.java — as specified in SPEC-rendering.md Section 4
   - Extends Pane, contains a Canvas
   - AnimationTimer calls ladderPainter.paint() every frame
   - Ctrl+scroll = zoom, scroll alone = vertical pan
   - Double-click resets userScrolled to false
   - MIN_ROW_PX=10, MAX_ROW_PX=60, default rowHeightPx=20

4. Update MuralisApp.java:
   - Read static fields snapshotRef, renderConfig, instrumentSpec,
     shutdownCallback
   - Create LadderCanvas and add to BorderPane CENTER
   - Add StatusBar (HBox, TOP) with placeholder "Connecting..." label
   - Add ControlBar (HBox, BOTTOM) with placeholder decay label
   - Wire stage onCloseRequest to call shutdownCallback.run() then
     Platform.exit()
   - No BubblePanel yet

Verify against SPEC-rendering.md Section 10 performance rules and
Section 11 invariant checklist. In particular:
- gc.clip() is never called
- All colors reference colorScheme fields — no hex literals in painters
- gc.save() / gc.restore() wraps every AnimationTimer frame
```

**After this step:** Run `./gradlew run`. You should see a live price
ladder with bid/ask bars updating in real time. Scroll and zoom should
work. This is the most visually satisfying milestone.

---

## Step 6 — Trade bubbles and side panel

**Objective:** Add the bubble panel to the right of the ladder. Trades
appear as coloured circles that drift and fade.

**What gets built:**
- `BubblePainter.java` — full implementation
- Updated `MuralisApp.java` — adds BubblePanel canvas to RIGHT slot

**What you will see:**
Green circles for aggressive buys, red circles for aggressive sells.
Circles appear at the price they traded, drift right, and fade out
over the decay window. Large trades produce larger circles.

**What can go wrong:**
- Bubbles at wrong vertical position → the price-to-pixel mapping must
  use the same `ViewState` as the ladder (shared scroll and zoom)
- All bubbles the same size → check the log formula uses `Math.log10`
  and the qty is correctly converted from fixed-point to double before
  the log calculation
- Bubbles not fading → check that `blip.receivedTs()` is used for the
  age calculation (not `exchangeTs` — see SPEC-rendering.md Section 6.3)

### Claude Code prompt — Step 6:
```
Read SPEC-rendering.md Sections 6 and 2 (layout).
Read DATA-CONTRACTS.md Section 2.2 (AggressorSide derivation) to
understand which bubble color maps to which side.

Generate:

1. BubblePainter.java — as specified in SPEC-rendering.md Section 6
   - Logarithmic size formula from Section 6.2 exactly
   - Alpha decay formula from Section 6.3 exactly — uses blip.receivedTs()
     not blip.exchangeTs() to avoid clock skew
   - Horizontal drift formula from Section 6.4 exactly — also uses receivedTs
   - Vertical position uses same price-to-pixel mapping as LadderPainter
   - Skip draw call entirely when alpha < 0.02
   - Paint qty label inside bubble only when diameter >= 18px
   - Do NOT call gc.clip() — the panel Canvas bounds drawing naturally

2. Update MuralisApp.java:
   - Add a second Canvas (280px wide) to BorderPane RIGHT slot
   - Pass the same snapshotRef and ViewState to BubblePainter
   - BubblePainter runs inside the same AnimationTimer as LadderPainter
   - Add a 1px vertical divider line between ladder and bubble panel

Do not change LadderPainter or LadderCanvas.
Verify: BUY bubbles use scheme.buyBubbleFill, SELL bubbles use
scheme.sellBubbleFill. AggressorSide.BUY = green = isBuyerMaker was false.
```

**After this step:** Run `./gradlew run`. You should see trades appearing
as coloured circles in the right panel, aligned with their price level
on the ladder, fading over 5 seconds.

---

## Step 7 — Status bar, control bar, and theme toggle

**Objective:** Add the connection status indicator, decay slider, zoom
buttons, and dark/light theme toggle. Wire the slider to `RenderConfig`.

**What gets built:**
- Updated `MuralisApp.java` — full StatusBar and ControlBar
- The amber pulse animation for CONNECTING/RECONNECTING states
- Theme toggle button that switches `ColorScheme`

**What you will see:**
A green dot in the top-left when connected. An amber pulsing dot
during reconnection. A working slider that changes how long bubbles
stay visible. A theme toggle that switches the entire canvas between
dark and light.

**What can go wrong:**
- Slider changes not reflected in bubble decay → confirm `renderConfig`
  is the same instance passed to both `OrderBookEngine` and `MuralisApp`
- Theme toggle affects StatusBar but not canvas → the `colorScheme`
  field on `LadderCanvas` and `BubblePainter` must both be updated
- Pulse animation does not stop on reconnect → confirm `Timeline.stop()`
  is called and opacity is reset to 1.0

### Claude Code prompt — Step 7:
```
Read SPEC-rendering.md Sections 7 (Status Bar) and 8 (Control Bar).

Update MuralisApp.java to add full StatusBar and ControlBar:

StatusBar (Section 7):
- 8px status dot (Circle), color driven by ConnectionState from snapshot
- CONNECTING and RECONNECTING: amber dot, pulsing via Timeline at 1Hz
- CONNECTED: green dot, no pulse
- DISCONNECTED: red dot, no pulse
- Symbol label "BTCUSDT"
- Theme toggle button that switches colorScheme on LadderCanvas and
  BubblePainter between ColorScheme.DARK and ColorScheme.LIGHT

ControlBar (Section 8):
- Decay label ("Decay: 5s")
- Slider: min=1, max=30, default=5, snap to 0.5 steps
- Slider listener writes seconds × 1000 to renderConfig.setBubbleDecayMs()
- "Centre" button resets ladderCanvas.resetScroll()
- "+" and "−" zoom buttons call ladderCanvas.adjustZoom(±2.0)

ConnectionState is read from snap.connectionState() in the AnimationTimer
— not from a separate subscription. The dot color updates every frame.

Do not modify LadderPainter, BubblePainter, or OrderBookEngine.
```

**After this step:** Run `./gradlew run`. Test every control manually:
- Drag the slider → bubbles should persist longer or shorter
- Click "−" zoom → ladder rows should compress
- Kill your network briefly → amber dot should appear, then green on reconnect

---

## Step 8 — Unit tests

**Objective:** Write the core unit tests that verify correctness of the
data pipeline without network access.

**What gets built:**
- `OrderBookTest.java` — tests for all `OrderBook` operations
- `TradeBufferTest.java` — tests for capacity, decay, deduplication
- `BinanceMessageParserTest.java` — tests for price/qty parsing accuracy
- `BootstrapSequenceTest.java` — tests for snapshot validation logic

**What you will see:**
`./gradlew test` passes with all tests green.

### Claude Code prompt — Step 8:
```
Read SPEC-engine.md Sections 3 and 4.
Read SPEC-ingestion.md Sections 3 and 8.
Read DATA-CONTRACTS.md Section 8 (invariant checklist).

Generate unit tests in src/test/java/com/muralis/ using JUnit 5 and AssertJ:

1. OrderBookTest.java:
   - applySnapshot populates bids and asks correctly
   - applyDelta with qty=0 removes the price level
   - applyDelta with qty>0 updates the price level
   - bestBid() returns highest bid price
   - bestAsk() returns lowest ask price
   - clear() empties both sides
   - setBid with qty=0 throws AssertionError (assert is enabled)

2. TradeBufferTest.java:
   - add() beyond MAX_BLIPS evicts the oldest blip
   - containsTradeId() returns true for added tradeId
   - containsTradeId() returns false after eviction
   - getActive() excludes blips where receivedTs is older than decayMs
   - getActive() uses receivedTs for the cutoff, not exchangeTs
   - clear() empties the buffer and seenTradeIds

3. BinanceMessageParserTest.java:
   - parsePrice("97432.51", priceScale=2) returns 9743251L
   - parsePrice("0.00", priceScale=2) returns 0L
   - parseQty("0.00041800", qtyScale=8) returns 41800L
   - isBuyerMaker=false → AggressorSide.BUY
   - isBuyerMaker=true  → AggressorSide.SELL
   - parsePrice with too many decimal places throws ArithmeticException

Use InstrumentSpec for BTCUSDT from DATA-CONTRACTS.md Section 5.1
as the test fixture. No mocking frameworks — use plain test data.
```

**After this step:** `./gradlew test` must pass. Do not proceed to
production use until all tests are green.

---

## Step 9 — Final smoke test and cleanup

**Objective:** Run the complete application, verify all subsystems work
together, and clean up any development scaffolding.

**Checklist before calling Phase 1 complete:**

- [ ] Application starts in under 2 seconds
- [ ] Order book syncs within 3 seconds of launch
- [ ] DOM ladder updates visibly as market moves
- [ ] Trade bubbles appear, colour correctly, and fade out
- [ ] Amber dot appears when network is interrupted
- [ ] Green dot returns after reconnection
- [ ] Decay slider changes bubble persistence visibly
- [ ] Zoom controls work
- [ ] Centre button returns ladder to mid-price
- [ ] Theme toggle switches between dark and light
- [ ] `./gradlew test` passes with all tests green
- [ ] No `OutOfMemoryError` after 30 minutes of running
- [ ] Console shows no ERROR-level log lines during normal operation
- [ ] Remove the temporary `bestBid/bestAsk` debug log added in Step 4

### Claude Code prompt — Step 9 (cleanup only):
```
Review Application.java and OrderBookEngine.java.

Remove any temporary debug log statements that were added during
development (e.g. the bestBid/bestAsk log in applySnapshot()).

Verify the PROVIDER SEAM comment block in Application.java matches
exactly what is specified in SPEC-provider-spi.md Section 5.

Verify the META-INF/services file at
src/main/resources/META-INF/services/com.muralis.provider.MarketDataProvider
contains exactly: com.muralis.ingestion.BinanceAdapter

No other changes. Do not refactor, do not add features, do not
"improve" anything that is already working.
```

---

## How to prompt Claude Code effectively

These rules apply to every prompt in this plan and any prompt you write
yourself.

### Rule 1: Always name the spec files first
Start every prompt with "Read [list of spec files]." Claude Code reads
the files in your project directory. Naming them explicitly tells Claude
which context is relevant for this task. Without this, Claude may
hallucinate types or miss constraints.

### Rule 2: One package at a time
Never ask Claude to generate `ingestion/` and `engine/` in the same
prompt. Each package has its own spec file and its own invariant
checklist. Generating one package at a time lets you verify correctness
before the next package depends on it.

### Rule 3: Always include the invariant checklist instruction
End every code generation prompt with:
```
Verify against [SPEC-file.md] Section [N] invariant checklist before
considering the task complete.
```
This instructs Claude to self-check its output before finishing.

### Rule 4: Say what NOT to generate
If you want `BinanceAdapter` but not `SnapshotFetcher` yet, say so:
```
Generate BinanceAdapter.java only. Do not generate SnapshotFetcher,
BinanceWebSocketClient, or BinanceMessageParser yet.
```
Claude Code defaults to generating everything it thinks is needed.
Explicit constraints keep each step small and reviewable.

### Rule 5: Forbid the things you know Claude gets wrong
Based on this project's constraints, always include:
```
Rules:
- No double or float for any price or quantity field
- No Spring, no Jakarta EE, no Lombok annotations
- No Thread.sleep() in the engine or ingestion layer
- No Platform.runLater() outside com.muralis.ui
```

### Rule 6: When something is wrong, show Claude the spec
If Claude generates code that violates a rule, do not just describe the
problem. Quote the spec:
```
This violates DATA-CONTRACTS.md Section 1.1 which states:
"Internal type: long (fixed-point). Forbidden types: double, float,
BigDecimal on any hot path."
Fix the OrderBook.bids field and all methods that set or return prices.
```
Quoting the spec gives Claude an unambiguous correction target.

### Rule 7: Never ask Claude to "improve" working code
Once a step passes its verification checklist, move on. Do not ask
Claude to refactor, optimise, or clean up code that works. Refactoring
introduces bugs. Each step in this plan is the minimum needed to verify
correctness — that is a feature, not a limitation.

### Rule 8: One session per step
Start a fresh Claude Code session for each step. This prevents context
from previous steps polluting current generation. The spec files provide
all the context Claude needs — it does not need to remember what it
generated three steps ago.

---

## What Phase 2 looks like (preview, not a plan)

Phase 2 begins only after Phase 1 passes all nine steps above with
full verification. The Phase 2 sequence will be:

1. Footprint candle data model (new types in `DATA-CONTRACTS.md`)
2. Candle accumulation in engine (new `FootprintEngine` class)
3. Footprint rendering (new `FootprintPainter` class)
4. Historical candle storage (Chronicle Queue or MapDB)
5. Second instrument support
6. Historical heatmap (a separate visual mode)

Each of these will get their own spec file before any code is generated.
The pattern is identical: spec first, decisions documented, then
incremental generation with verification at each step.

---

*DEV-PLAN.md v1.1 — Step 1 corrected (InstrumentSpec not MarketEvent, package placement clarified). Steps 4-8 updated for renamed fields, receivedTs, shutdownCallback, and clip fix.*
*Do not begin Step 1 until Step 0 is verified working.*
