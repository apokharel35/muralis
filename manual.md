# DECISIONS.md — Muralis Decision Guide & User Manual

> This document explains every major decision made during the design of
> Muralis in plain language. It also serves as a navigation guide for
> new developers joining the project. You do not need a trading or
> systems programming background to understand this document.

---

## Part 1: What is Muralis and why does it exist?

Muralis is a desktop application that shows you what is happening inside
a financial market in real time. Think of it like an X-ray machine for
the order book — it reveals the hidden structure of supply and demand
that drives price movement.

The tools that do this today (Bookmap, Sierra Chart) are either very
expensive, very heavy on your computer's resources, or both. Bookmap
alone recommends 32 GB of RAM and an i9 processor just to run it. Our
goal is to build something that does the same core job in under 2 GB of
RAM, starts in under 2 seconds, and feels fast.

The name Muralis comes from the Latin word "murus" meaning wall. The
tool is designed to let you see beyond the walls of the order book —
beyond what the surface price tells you.

---

## Part 2: The big decisions explained simply

### Decision 1 — No Spring Boot

**What we decided:** We are not using Spring Boot, even though the lead
developer uses it every day.

**Why in plain terms:** Spring Boot is like ordering a full restaurant
kitchen when all you need is a camping stove. It was built to run web
servers — things that handle HTTP requests from browsers. When you start
a Spring Boot app, it automatically sets up a web server (Tomcat), a
database connection pool, a JSON serialiser, a metrics system, and
dozens of other things — even if you tell it not to. All of that setup
takes time (2+ seconds) and memory (400+ MB) before your app does
anything useful.

Muralis is a desktop app. It has no web server. It has no database. It
has no HTTP endpoints. Using Spring Boot here is like arriving at a
camping trip in a semi-truck — technically it can carry your tent, but
it is completely the wrong tool.

**What we use instead:** Plain Java. We create objects by writing
`new BinanceAdapter(queue, spec)` in one place — the `Application.java`
file. About 15 lines of wiring code. Zero framework overhead.

**Where to change this:** `ARCHITECTURE.md` Section 6 (Technology
Decisions) and `BUILD.md` Section 4 (the dependencies list). If you
ever want to add a DI framework, those are the two files to update.

---

### Decision 2 — JavaFX Canvas for rendering (not LWJGL/OpenGL)

**What we decided:** We draw the price ladder and trade bubbles using
JavaFX's built-in 2D canvas, not a direct GPU library.

**Why in plain terms:** There are two ways to draw things on screen with
Java. The first is JavaFX Canvas — it is like drawing on a whiteboard.
You say "draw a green rectangle here, draw some text there" and it
handles the rest. The second is LWJGL/OpenGL — it is like programming
the graphics card directly. You write shader code in a separate
language, manage GPU memory buffers yourself, and deal with concepts
like VAOs, VBOs, and uniform variables just to draw a coloured box.

For a 5-day MVP with one developer, LWJGL would consume the entire
week before a single rectangle appears on screen. JavaFX Canvas can
handle thousands of rectangles at 60 frames per second — which is
exactly what we need for a price ladder with bid/ask bars.

The honest truth: the rendering bottleneck for a tool like this is not
the drawing — it is the data pipeline. Getting the order book right is
harder than drawing it. JavaFX lets us focus on the hard part.

**What we use:** JavaFX Canvas with an `AnimationTimer` that redraws
60 times per second.

**If performance becomes a problem later:** The upgrade path is
documented in `SPEC-rendering.md` Section 10. The most likely
optimisation is using JavaFX's `PixelBuffer` API to write pixels
directly rather than using draw calls — this stays within JavaFX and
requires no new framework.

**Where to change this:** `SPEC-rendering.md` — the entire file
describes the rendering approach. `ARCHITECTURE.md` Section 6 has
the locked decision with rationale.

---

### Decision 3 — Three threads, no more

**What we decided:** The entire application runs on exactly three
threads — one for network data, one for processing, one for drawing.

**Why in plain terms:** Think of a restaurant. You have one person
taking orders from customers (the network thread), one person in the
kitchen cooking (the engine thread), and one person serving food to
the table (the UI thread). If the cook also tried to take orders and
serve food simultaneously, orders would get mixed up and food would
arrive at the wrong tables.

In software, threads sharing work without clear boundaries produce the
same result — scrambled data, crashes, and bugs that only appear
occasionally and are nearly impossible to reproduce.

Our three-thread model has strict rules: the network thread never
touches the drawing code, the drawing code never touches the order book
directly, and the cooking (engine) thread is the only one allowed to
modify the order book state.

**How data moves between threads:**
- Network thread → Engine thread: via a queue (like a ticket printer
  in a restaurant passing orders to the kitchen)
- Engine thread → UI thread: via an `AtomicReference` — the engine
  writes a fresh "menu of current prices" every time it processes an
  event, and the UI reads the latest one 60 times per second

**Where to change this:** `ARCHITECTURE.md` Section 2 (Thread Model).
Any change to the thread model requires updating this section first,
and then all four SPEC files that reference threading behaviour.

---

### Decision 4 — Prices stored as whole numbers (fixed-point longs)

**What we decided:** Every price and quantity in the system is stored
as a whole number (`long`), not a decimal (`double`).

**Why in plain terms:** Computers cannot represent most decimal numbers
exactly. The number `97432.51` stored as a `double` is actually stored
as `97432.50999999999417923390865325927734375`. That tiny error, when
multiplied across thousands of calculations, produces wrong numbers.
In a trading tool, a wrong price is not a minor cosmetic issue — it is
a fundamental correctness failure.

The solution is to work with whole numbers throughout. Instead of
storing `97432.51`, we store `9743251` and remember that the last two
digits are after the decimal point. When we need to show the price to
the user, we divide by 100 at the very last moment. This way, all
calculations are exact integer arithmetic with no rounding errors.

**The technical term for this:** Fixed-point arithmetic with a scale
factor. For Bitcoin prices (2 decimal places), the scale is 100 (10²).
For quantities like `0.00041800` BTC (8 decimal places), the scale is
100,000,000 (10⁸).

**Where to change this:** `DATA-CONTRACTS.md` Section 1 (Primitive Type
Rules) and Section 5 (Instrument Specifications). If you add a new
instrument with different decimal precision, add its `priceScale` and
`qtyScale` to Section 5.

---

### Decision 5 — Exchange timestamps, never local clock

**What we decided:** Every event is timestamped using the time the
exchange says it happened, not the time our computer received it.

**Why in plain terms:** Imagine you are watching a live sports match on
TV with a 3-second broadcast delay. If you use your watch to record
"when the goal was scored", you will be 3 seconds wrong. The correct
time is what the stadium clock shows at the moment of the goal.

Our computer receives market data with a delay — sometimes 10ms,
sometimes 200ms depending on network conditions. If we stamp events
with our local clock, every candle and bubble will be placed at the
wrong time. We use the exchange's timestamp (the stadium clock) instead.

**The one exception:** When calculating how old a trade bubble is for
the fade-out animation, we use `receivedTs` — the local time the trade
arrived on this machine. This is a display decision, not a data decision
— we are asking "how long has this bubble been on my screen?" not "when
did the trade happen on the exchange?". Using `exchangeTs` here would
cause problems if the exchange clock and local clock differ by even a
few hundred milliseconds: new trades could appear partially faded or
frozen. `TradeBlip` carries both `exchangeTs` (for data correctness)
and `receivedTs` (for decay animation).

**Where to change this:** `DATA-CONTRACTS.md` Section 1.3 (Timestamp
rules). The distinction between `exchangeTs` and `receivedTs` is defined
there. Every spec file references these field names. The bubble decay
formula is in `SPEC-rendering.md` Section 6.3.

---

### Decision 6 — Full order book sent to UI, trimmed at render time

**What we decided:** The engine sends every price level it knows about
to the UI. The UI decides how many rows to show based on the screen size
and zoom level.

**Why in plain terms:** Imagine a bookshelf with 2,000 books. You could
either send the person a list of only the 50 books nearest to eye level
(the engine decides), or send them the full catalogue and let them
browse to wherever they are looking (the UI decides).

We chose the second approach because it means the engine does not need
to know anything about the screen — it does not know how big the window
is, what zoom level the trader is using, or where they have scrolled.
That is a rendering concern, not a data concern. Clean separation makes
both parts simpler.

**Performance note:** A typical Bitcoin order book has 500–2,000 price
levels. Sending 2,000 pairs of numbers 60 times per second is about
960,000 numbers per second — trivially fast for modern hardware.

**Where to change this:** `SPEC-engine.md` Section 2.7 (buildSnapshot)
and `SPEC-rendering.md` Section 5.2 (Visible Price Levels). If you ever
want to cap the depth at the engine level for performance reasons, the
change goes in `SPEC-engine.md`.

---

### Decision 7 — Logarithmic bubble sizing

**What we decided:** Trade bubble diameters grow logarithmically with
trade quantity, not linearly.

**Why in plain terms:** Bitcoin trades range from 0.001 BTC (tiny retail
order) to 100 BTC (large institutional block). If bubble size were
linear, a 100 BTC trade would produce a bubble 100,000 times larger than
a 0.001 BTC trade — the large bubble would cover the entire screen and
make small trades invisible.

Logarithmic scaling compresses this range. A trade 1,000 times larger
only produces a bubble about 3 times bigger (because log₁₀(1000) = 3).
This keeps all trades visible and readable regardless of size, while
still clearly showing which trades are large.

**Where to change this:** `SPEC-rendering.md` Section 6.2 (Bubble
Sizing). The formula is written out with worked examples for BTC trade
sizes. The minimum and maximum diameter constants are also there.

---

### Decision 8 — Immediate retry then exponential backoff on disconnect

**What we decided:** When the connection drops, we try to reconnect
immediately three times. After the third failure, we wait — starting
at 500ms and doubling up to a maximum of 30 seconds.

**Why in plain terms:** Most disconnections are momentary network blips
that resolve in under a second. Waiting before the first retry would
add unnecessary delay for the most common case. But if the exchange is
genuinely down, hammering it with constant reconnection attempts is
wasteful and rude. The backoff schedule handles both cases: fast
recovery for blips, polite patience for genuine outages.

**Where to change this:** `SPEC-ingestion.md` Section 5.2 (Reconnection
Backoff Schedule). The attempt thresholds and wait times are defined
there as a simple table.

---

### Decision 9 — Discard missed trades during reconnect, show UI indicator

**What we decided:** If trades happen while we are reconnecting, we do
not try to fetch them retroactively. Instead, we show an amber status
dot so the trader knows the feed was interrupted.

**Why in plain terms:** The alternative — fetching missed trades from
Binance's historical API and replaying them — sounds appealing but adds
significant complexity. What if the historical API is also slow? What if
there are 10,000 trades in the gap? When exactly do we switch from
replay to live?

The amber dot is honest. It says: "the feed was interrupted, some
bubbles may be missing during this period." A serious trader will see
the dot, note the time, and factor it into their analysis. Silent
incorrect data is far worse than clearly indicated missing data.

**Where to change this:** `SPEC-ingestion.md` Section 5.4 (Trades
During Reconnection) for the discard behaviour. `SPEC-rendering.md`
Section 7 (Status Bar) for the indicator colour and pulse animation.
Section 5.4 also documents the Phase 2 upgrade path for REST backfill.

---

### Decision 10 — Hardcoded provider for MVP, ServiceLoader seam preserved

**What we decided:** For Phase 1, we write `new BinanceAdapter(...)` 
directly in `Application.java`. For Phase 2+, the same line gets
replaced with a ServiceLoader discovery pattern.

**Why in plain terms:** ServiceLoader is Java's built-in plugin system.
It lets you swap one implementation for another without changing code —
just by changing a configuration file. For Phase 2 where we add Rithmic
or CQG, this is the right pattern.

But for a 5-day MVP with one provider, setting up ServiceLoader is like
installing a revolving door on a tent. We mark the exact line in code
with a comment that says "this is the seam — replace this line in Phase
2." The architecture is designed so that swap is genuinely one line.

**Where to change this:** `Application.java` (the comment-marked seam),
`SPEC-provider-spi.md` Section 5 (Phase 1 pattern) and Section 6
(Phase 2 ServiceLoader upgrade procedure).

---

## Part 3: How the spec files relate to each other

Think of the spec files as a dependency chain. Each file assumes the
previous ones have been read. When you open any spec file, everything
it references by name is defined in a file earlier in the chain.

```
PROJECT.md          "What are we building and why?"
    ↓
DATA-CONTRACTS.md   "What does every piece of data look like?"
    ↓
ARCHITECTURE.md     "How do the pieces connect and who owns what?"
    ↓
SPEC-ingestion.md   "How does data get in from Binance?"
    ↓
SPEC-engine.md      "How does data get processed?"
    ↓
SPEC-rendering.md   "How does processed data get drawn on screen?"
    ↓
SPEC-provider-spi.md "How do we swap Binance for another provider later?"
    ↓
BUILD.md            "How do we compile and run the whole thing?"
```

You never need to read all eight files to make a change. The table in
Part 4 tells you which file to open for any type of change.

---

## Part 4: New developer navigation guide

### "I want to change how prices look on screen (formatting, decimal places)"
→ Open `DATA-CONTRACTS.md` Section 5 (Instrument Specifications)
→ Then `SPEC-rendering.md` Section 5.6 (Price Text Painting)

### "I want to change the colors"
→ Open `SPEC-rendering.md` Section 3 (ColorScheme)
→ All colors live in `ColorScheme.java` — nowhere else

### "I want to add a new instrument (e.g. SOLUSDT)"
→ Open `DATA-CONTRACTS.md` Section 5.1 (Binance Spot instruments)
→ Add the new `InstrumentSpec` row with correct `priceScale`, `tickSize`,
  `qtyScale`, and `minQty` values
→ No other spec files need changing for a new Binance instrument

### "I want to change how big trade bubbles are"
→ Open `SPEC-rendering.md` Section 6.2 (Bubble Sizing)
→ The formula, minimum diameter, and maximum diameter are all there

### "I want to change the reconnection retry timing"
→ Open `SPEC-ingestion.md` Section 5.2 (Reconnection Backoff Schedule)

### "I want to add a second exchange (e.g. Coinbase)"
→ Read `SPEC-provider-spi.md` Section 6 (ServiceLoader upgrade path)
→ Read `SPEC-ingestion.md` to understand what the Binance adapter
  does — your new adapter must satisfy the same behavioural contract
→ Add the new `ProviderType` enum value to `DATA-CONTRACTS.md` Section 4.1

### "I want to understand the thread model"
→ Open `ARCHITECTURE.md` Section 2 (Thread Model)
→ This is the most important section for understanding why the code is
  structured the way it is

### "I want to add disk persistence / historical data"
→ This is a Phase 2 feature — read `SPEC-engine.md` Section 9
  (What This Spec Excludes) for the planned seam
→ The engine's `TradeBuffer` and `OrderBook` are the starting points
  for what needs to be persisted

### "I want to understand why Spring Boot was rejected"
→ Read `DECISIONS.md` Decision 1 (this file, above)
→ Read `ARCHITECTURE.md` Section 6 (Technology Decisions)

### "I want to add footprint charts (Phase 2)"
→ Read `PROJECT.md` Section 3 (Phase 2 Scope)
→ Read `SPEC-engine.md` Section 9 (Phase 2 exclusions and seams)
→ The `FootprintCandle` data model will be added to `DATA-CONTRACTS.md`
  as a new section when Phase 2 begins

### "Something is wrong with the order book state"
→ The order book logic lives entirely in `SPEC-engine.md` Section 3
→ The bootstrap sequence (how the book is initially populated) is in
  `SPEC-ingestion.md` Section 3
→ Gap detection (what happens when updates arrive out of order) is in
  `SPEC-ingestion.md` Section 4

### "I want to change the window layout"
→ Open `SPEC-rendering.md` Section 2 (Window and Layout Structure)
→ The BorderPane slots and initial dimensions are defined there

---

## Part 5: Rules every developer must know

These are not suggestions. They are architectural invariants. Breaking
any of them without updating the relevant spec file and adding an ADR
to `ARCHITECTURE.md` Section 8 constitutes technical debt.

**Rule 1: Prices are always `long`. Never `double`.**
If you see a `double` field storing a price anywhere in
`com.muralis.model`, `com.muralis.engine`, or `com.muralis.ingestion`,
it is a bug. Fix it.

**Rule 2: The UI thread never blocks.**
`LadderCanvas` and `BubblePainter` may not call any method that waits
for a network response, reads from disk, or polls a queue with a
timeout. The only data they read is `snapshotRef.get()` — an atomic
single-instruction read.

**Rule 3: The engine thread never touches JavaFX.**
`OrderBookEngine` and `OrderBook` have zero knowledge that JavaFX
exists. No `Platform.runLater()`, no `javafx.*` imports.

**Rule 4: Only `BinanceAdapter` knows about `isBuyerMaker`.**
The field `m` from Binance's JSON means "is the buyer the maker?" and
determines trade direction. The conversion from this boolean to
`AggressorSide.BUY` or `AggressorSide.SELL` happens once, in
`BinanceMessageParser`. No other class ever looks at this field.

**Rule 5: `Application.java` is the only class that knows everything.**
It is the composition root — the one place that creates all the objects
and connects them. It contains no business logic. If you find yourself
writing an `if` statement in `Application.java` for anything other than
a null check, something has gone wrong with the design.

---

## Part 6: Glossary of terms used across all spec files

| Term | Plain English explanation |
|---|---|
| **Order book** | The live list of all buyers (bids) and sellers (asks) and how much they want to buy or sell at each price |
| **Bid** | A resting buy order — someone willing to buy at this price or lower |
| **Ask** | A resting sell order — someone willing to sell at this price or higher |
| **Spread** | The gap between the best bid and the best ask — the cost of trading immediately |
| **Delta** | An update message that says "change this price level by this amount" |
| **Snapshot** | A complete picture of the order book at one moment in time |
| **Sequence ID** | A serial number on each delta update — used to detect if any updates were missed |
| **Gap** | A missing sequence ID — means we lost some updates and the order book may be wrong |
| **Aggressor** | The trader who crossed the spread to trade immediately (paid the spread cost) |
| **Maker** | The trader whose resting limit order was filled by the aggressor |
| **Fixed-point** | Storing decimals as whole numbers by remembering where the decimal point goes |
| **Footprint candle** | A price candle that shows how much volume traded at each price level (Phase 2) |
| **Provider** | The code that connects to a specific exchange (Binance, Rithmic, etc.) |
| **Engine thread** | The background thread that maintains the order book state |
| **Ingestion thread** | The background thread that receives raw data from the exchange |
| **UI thread** | The thread that draws on the screen — JavaFX owns this thread |
| **AtomicReference** | A thread-safe box that holds one object — one thread writes, another reads |
| **Sealed interface** | A Java type that declares its complete list of subtypes — enables exhaustive switch |

---

*DECISIONS.md v1.1 — Updated Decision 5 to clarify receivedTs usage for bubble decay.*
