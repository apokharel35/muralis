# SPEC-phase4-heatmap.md — Muralis Phase 4

> This spec defines the Phase 4 feature: a scrolling liquidity heatmap
> with volume dots and BBO price lines, rendered on a new LEFT pane.
> This is the "Bookmap view" — the signature visualization that makes
> Muralis comparable to professional order flow tools.
>
> **Prerequisites:** Phase 3 complete (volume profile). All unit tests
> passing. BubblePainter retained in codebase.

---

## 1. Feature summary

### What the trader sees

A new pane appears to the LEFT of the DOM ladder. Time flows
left-to-right — the right edge is "now," older data scrolls left.
The pane shows three overlaid layers:

**Layer 1 — Resting liquidity heatmap (background)**
Each cell at (time, price) is colored by the resting order book depth
at that price at that moment. Darker/cooler colors = thin liquidity.
Brighter/warmer colors = thick liquidity. This reveals where limit
orders were placed, held, pulled, or consumed over time.

**Layer 2 — Volume dots (foreground)**
Circles plotted at the (time, price) coordinate where each trade
executed. Green = aggressive buy, red = aggressive sell. Size
proportional to quantity (same logarithmic formula as BubblePainter).
Volume dots are **permanent** — they do not decay or fade. They scroll
left with time and eventually exit the visible window.

**Layer 3 — BBO price lines (overlay)**
Two thin lines tracing the best bid (green) and best ask (red) through
time. These show the path of the market — where price traveled, how
fast it moved, and where it paused.

### Layout after Phase 4
```
[ heatmap ~600px ] [ ladder 700px ] [ vol profile 280px ]
```

Total default width: ~1580px. Minimum window width increases from
900px to 1500px to accommodate the heatmap pane. The heatmap pane
is resizable with the window (flexes with available space).

### What does NOT change
- The DOM ladder (bid/ask bars, delta tint, price text, spread)
- The volume profile pane (right side)
- The engine thread model (3 threads)
- The ingestion layer
- No new external dependencies
- Fixed-point arithmetic rules

---

## 2. HeatmapBuffer — the time-series data structure

This is the most important architectural component in Phase 4.

### 2.1 Overview

`HeatmapBuffer` is a fixed-capacity ring buffer of `HeatmapColumn`
objects. Each column represents a snapshot of the entire order book
at one point in time, plus any trades that occurred in that time
window.

```java
package com.muralis.engine;

public class HeatmapBuffer {
    private final HeatmapColumn[] columns;
    private volatile int writeIndex;    // monotonically increasing
    private final int capacity;

    public HeatmapBuffer(int capacity) {
        this.columns = new HeatmapColumn[capacity];
        this.capacity = capacity;
        this.writeIndex = 0;
    }
}
```

### 2.2 `HeatmapColumn` — immutable time slice

```java
package com.muralis.engine;

public record HeatmapColumn(
    long          timestamp,    // System.currentTimeMillis() at capture
    long          bestBid,      // for BBO line rendering
    long          bestAsk,      // for BBO line rendering
    long[]        prices,       // ALL book levels, sorted ascending
    long[]        quantities,   // resting qty at each price (parallel)
    TradeBlip[]   trades        // trades that occurred in this time window
) {}
```

**Price and quantity arrays** contain the COMBINED bid+ask depth.
For each price level in the order book, the quantity is the resting
qty at that level (bid qty if below best ask, ask qty if above best
bid; prices inside the spread have qty 0 and are omitted).

**Trades array** holds all `TradeBlip` objects that occurred between
the previous column's timestamp and this column's timestamp. This
replaces `recentTrades` as the source for volume dot rendering on
the heatmap — the painter iterates columns, not the flat
`recentTrades` list.

**Immutability:** `HeatmapColumn` is a record with final fields. The
arrays are created at construction time and never modified. This is
critical for thread safety — the UI thread reads columns that were
written by the engine thread, and immutability guarantees consistency
without locking.

### 2.3 Ring buffer write (engine thread only)

```java
// Engine thread — called every HEATMAP_INTERVAL_MS:
void writeColumn(HeatmapColumn column) {
    columns[writeIndex % capacity] = column;
    writeIndex++;   // volatile write — ensures visibility to UI thread
}
```

`writeIndex` is monotonically increasing (never wraps — the modulo
happens at read time). This means `writeIndex` serves double duty:
- `writeIndex % capacity` → the slot to write to
- `writeIndex` → the total number of columns written (for age calc)

At 10 columns/sec, `writeIndex` reaches `Integer.MAX_VALUE` after
~6.8 years of continuous operation. No overflow concern.

### 2.4 Ring buffer read (UI thread)

```java
// UI thread — called during paint:
HeatmapColumn getColumn(int index) {
    if (index < 0 || index < writeIndex - capacity) return null;
    return columns[index % capacity];
}

int getWriteIndex() { return writeIndex; }
int getCapacity()   { return capacity; }
```

The UI reads `writeIndex` (volatile read — sees latest value), then
reads columns backwards from `writeIndex - 1` to
`writeIndex - visibleColumns`. Since columns are immutable records,
the UI always sees a consistent column even if the engine writes a
new column to a different slot concurrently.

### 2.5 Thread safety argument

**Why this works without locking:**

1. `HeatmapColumn` is immutable. Once constructed by the engine and
   written to the array slot, its contents never change.

2. `writeIndex` is volatile. The UI thread's volatile read of
   `writeIndex` establishes a happens-before relationship with the
   engine's volatile write, guaranteeing that all columns written
   before `writeIndex` was updated are visible to the UI.

3. The engine only overwrites columns that are `capacity` slots
   behind the current write position. The UI reads columns within
   `[writeIndex - visibleColumns, writeIndex - 1]`. As long as
   `visibleColumns < capacity`, the engine never overwrites a column
   the UI is reading.

4. **Safety margin:** The buffer capacity is set to `timeWindowSec ×
   columnsPerSec + SAFETY_MARGIN`. The safety margin (default: 20
   columns = 2 seconds) ensures the engine never catches up to the
   UI's read window even under worst-case frame scheduling.

**No AtomicReferenceArray needed.** The volatile write to `writeIndex`
after the array store provides sufficient ordering. The array store
itself is not volatile, but the subsequent volatile write to
`writeIndex` flushes the array store to main memory (JSR-133
happens-before transitivity).

### 2.6 Memory analysis

**Per-column cost:**
```
HeatmapColumn record overhead:              ~48 bytes
timestamp + bestBid + bestAsk:               24 bytes
prices array (2000 levels typical):      16,000 bytes
quantities array (2000 levels):          16,000 bytes
trades array (avg 5 trades × ~80 bytes):    400 bytes
Array object headers (3 arrays):             48 bytes
────────────────────────────────────────────────────
Total per column:                        ~32.5 KB
```

**Buffer capacity at different time windows:**

| Time window | Columns (at 100ms) | Memory |
|---|---|---|
| 30 seconds | 300 + 20 safety = 320 | 10.4 MB |
| 60 seconds (default) | 600 + 20 = 620 | 20.2 MB |
| 120 seconds | 1200 + 20 = 1220 | 39.7 MB |
| 300 seconds (5 min) | 3000 + 20 = 3020 | 98.2 MB |

**At the default 60-second window: ~20 MB.** This is 2% of the 1 GB
heap. At the maximum recommended 5-minute window: ~100 MB = 10% of
heap. Both are acceptable with ZGC.

**GC impact:** 10 new columns/sec × 32.5 KB = 325 KB/sec of
allocations. Old columns become garbage when overwritten. ZGC
generational mode handles this pattern well — short-lived allocations
in the young generation, no stop-the-world pauses.

### 2.7 Reconnect behavior

On `CONNECTING` or `RECONNECTING`:

```java
void clear() {
    Arrays.fill(columns, null);
    writeIndex = 0;
}
```

The entire heatmap history is discarded. The order book is being
rebuilt from scratch — historical depth data from the previous
connection is meaningless. The heatmap starts fresh from the new
snapshot.

### 2.8 Passing to UI via RenderSnapshot

`RenderSnapshot` carries a **reference** to the `HeatmapBuffer`
itself, not a copy. Since `HeatmapColumn` objects are immutable and
the UI reads via `writeIndex` (volatile), this is thread-safe.

```java
public record RenderSnapshot(
    // ... existing fields ...
    HeatmapBuffer heatmapBuffer   // reference, NOT a copy
) {}
```

The `HeatmapBuffer` reference is set once in the engine constructor
and never changes. Every `RenderSnapshot` carries the same reference.
The UI accesses the latest data via `heatmapBuffer.getWriteIndex()`.

---

## 3. Engine changes — `OrderBookEngine`

### 3.1 New fields

```java
private final HeatmapBuffer heatmapBuffer;
private long lastHeatmapColumnTs = 0L;
private final List<TradeBlip> pendingHeatmapTrades = new ArrayList<>();

private static final long HEATMAP_INTERVAL_MS = 100L;  // one column per 100ms
```

### 3.2 Constructor change

```java
// HeatmapBuffer capacity from RenderConfig:
int capacity = (int)(renderConfig.heatmapTimeWindowSec() * 10) + 20;
this.heatmapBuffer = new HeatmapBuffer(capacity);
```

The `+20` is the safety margin (2 seconds at 10 columns/sec).

### 3.3 `applyTrade` modification

After the existing duplicate check, TradeBlip creation,
deltaAccumulator, and volumeAccumulator calls, add:

```java
pendingHeatmapTrades.add(blip);
```

This collects trades that will be bundled into the next
`HeatmapColumn`. The list is flushed when the column is built.

### 3.4 Heatmap column generation — time-based throttle

In the `runLoop()`, AFTER processing the event and BEFORE
`buildSnapshot()`:

```java
long now = System.currentTimeMillis();
if (now - lastHeatmapColumnTs >= HEATMAP_INTERVAL_MS) {
    heatmapBuffer.writeColumn(buildHeatmapColumn(now));
    lastHeatmapColumnTs = now;
}
```

### 3.5 `buildHeatmapColumn` implementation

```java
private HeatmapColumn buildHeatmapColumn(long timestamp) {
    // 1. Merge bid and ask into a single sorted-ascending price array
    Set<Map.Entry<Long,Long>> bids = orderBook.getBidsDescending();
    Set<Map.Entry<Long,Long>> asks = orderBook.getAsksAscending();

    int totalLevels = bids.size() + asks.size();
    long[] prices = new long[totalLevels];
    long[] quantities = new long[totalLevels];

    // Fill asks first (ascending), then bids (reversed to ascending)
    int i = 0;
    // Bids: iterate in ASCENDING order (reverse of descending)
    List<Map.Entry<Long,Long>> bidList = new ArrayList<>(bids);
    Collections.reverse(bidList);
    for (Map.Entry<Long,Long> entry : bidList) {
        prices[i] = entry.getKey();
        quantities[i] = entry.getValue();
        i++;
    }
    for (Map.Entry<Long,Long> entry : asks) {
        prices[i] = entry.getKey();
        quantities[i] = entry.getValue();
        i++;
    }
    // Array is now sorted ascending (bids low→high, then asks low→high)

    // 2. Collect pending trades
    TradeBlip[] trades = pendingHeatmapTrades.toArray(TradeBlip[]::new);
    pendingHeatmapTrades.clear();

    // 3. BBO
    long bestBid = orderBook.bestBid();
    long bestAsk = orderBook.bestAsk();

    return new HeatmapColumn(timestamp, bestBid, bestAsk,
                              prices, quantities, trades);
}
```

**Performance:** `buildHeatmapColumn` is called 10 times/sec (not 100).
The TreeMap iteration + ArrayList reverse + array allocation is
~0.1ms per call. Well within budget.

### 3.6 `applyConnectionEvent` modification

On `CONNECTING` and `RECONNECTING`:

```java
heatmapBuffer.clear();
pendingHeatmapTrades.clear();
lastHeatmapColumnTs = 0L;
```

### 3.7 `buildSnapshot` modification

Add heatmap buffer reference:

```java
// In buildSnapshot():
return new RenderSnapshot(
    // ... existing fields ...
    heatmapBuffer    // same reference every time
);
```

---

## 4. Answers to critical design questions

### 4a. Snapshot frequency
**Time-based at 100ms intervals**, not event-based. The engine checks
`System.currentTimeMillis()` after each event and writes a column when
≥100ms have elapsed since the last column. This gives exactly 10
columns/sec regardless of market activity.

**Why not per-event (100/sec):**
- 3× more columns = 3× more memory (~60 MB for 60 seconds)
- Most columns would be visually identical (book changes 1-2 levels)
- 100 columns/sec at 600px panel width = 6 columns per pixel for a
  60-second window — wasted resolution that the human eye can't see

**Why not slower (500ms or 1sec):**
- 2 columns/sec creates a visibly "blocky" heatmap — the trader sees
  discrete jumps instead of smooth liquidity flow
- Bookmap updates at ~30ms but 100ms is the minimum for smooth visual
  perception at typical zoom levels

### 4b. Cell resolution — what is one column?
One column = **100 milliseconds** of real time. At the default 60-second
time window and ~600px panel width, each column maps to ~1 pixel.
When the time window is shorter (30 sec), columns are ~2px wide.
When longer (120 sec), the painter aggregates 2 columns per pixel
(taking the max quantity at each price).

### 4c. Price resolution
**Every tick** — same as the ladder. The heatmap shows one row per
`tickSize` increment. For BTCUSDT (`tickSize=1L`, `priceScale=2`):
every 0.01 price increment. For CME ES (`tickSize=25L`): every 0.25
increment. The heatmap uses the same `priceToY()` function as the
ladder and volume profile.

### 4d. Color scale
**Relative to visible maximum.** Each frame, the painter finds the max
quantity across all visible cells (visible time range × visible price
range). This max is the "hottest" color. All other cells are
proportional. An intensity slider scales this mapping.

This auto-adapts to different instruments and market conditions. BTC
at $67K with 10 BTC at the best level produces the same visual
intensity as ES at $5400 with 500 contracts.

### 4e. Volume dot behavior
**Persistent — no decay.** Volume dots are plotted at their (time,
price) coordinate and remain there permanently. They scroll left with
time and eventually exit the visible window. This matches Bookmap
behavior. The old `bubbleAlpha()` decay formula is NOT used for
heatmap volume dots.

**Sizing** uses the same logarithmic formula from BubblePainter
Section 6.2 of SPEC-rendering.md. Green for `AggressorSide.BUY`,
red for `SELL`. Full opacity (alpha 1.0) — no fading.

### 4f. Off-screen price levels
**Stored in full.** Each `HeatmapColumn` stores ALL price levels in
the order book, not just the visible range. If the trader scrolls the
ladder vertically, previously off-screen heatmap data becomes visible
immediately — no refetching, no blanks.

The painter only iterates levels within the visible Y range (same
performance guard as the ladder painter). Off-screen levels exist in
the data but are never drawn.

### 4g. Memory ceiling
**The HeatmapBuffer is the only significant memory consumer.**

| Time window | Memory | % of 1GB heap |
|---|---|---|
| 60 sec (default) | ~20 MB | 2% |
| 120 sec | ~40 MB | 4% |
| 300 sec (max) | ~100 MB | 10% |

The maximum recommended time window is 300 seconds (5 minutes).
Beyond this, memory consumption exceeds 10% of the heap and GC
pressure becomes meaningful. `RenderConfig` enforces this ceiling.

---

## 5. HeatmapPainter — new class

```java
package com.muralis.ui;

public class HeatmapPainter {

    public void paint(
        GraphicsContext  gc,
        RenderSnapshot   snap,
        ViewState        view,
        ColorScheme      scheme,
        RenderConfig     renderConfig
    )
}
```

### 5.1 Panel layout

```
panelWidth = flexible (fills LEFT slot, ~600px default)

Time axis: left = oldest, right = newest ("now")
Price axis: shared with ladder (same priceToY, same scroll, same zoom)

┌──────────────────────────────────────────────┐
│ oldest ←───── time ──────→ newest            │
│                                              │
│  ░░░░░░░░░██████░░░░░░░░░░░████████████     │ ← bright = thick liquidity
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░     │
│  ░░░░████████░░░░░░░░░██████░░░░░░░░░░░     │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░     │
│  ██████████████████████████████████████░     │ ← BBO line path
│  ░░░░░░░░░░●░░░░░░░░░░░●░░░░░░░░░●░░░░     │ ← volume dots
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░     │
│  ░░░░░░█████████░░░░░░░░████████████░░░     │
└──────────────────────────────────────────────┘
```

### 5.2 Time-to-X mapping

```java
double timeToX(long timestamp, long newestTs, long timeWindowMs,
               double panelWidth) {
    long age = newestTs - timestamp;
    if (age < 0 || age > timeWindowMs) return -1;  // off-screen
    double ratio = 1.0 - ((double) age / timeWindowMs);  // 0=oldest, 1=newest
    return ratio * panelWidth;
}
```

The newest column maps to `x = panelWidth` (right edge). The oldest
visible column maps to `x = 0` (left edge). Columns older than the
time window are not drawn.

### 5.3 Paint sequence (per frame)

```
0. Read heatmapBuffer reference and writeIndex from snap
   If heatmapBuffer is null or writeIndex == 0: fill background, return
   If !renderConfig.heatmapEnabled(): fill background, draw divider, return

1. Fill panel background
   gc.setFill(scheme.heatmapBackground)
   gc.fillRect(0, 0, panelWidth, panelHeight)

2. Determine visible time range
   newestTs = heatmapBuffer.getColumn(writeIndex - 1).timestamp()
   timeWindowMs = renderConfig.heatmapTimeWindowSec() * 1000L
   oldestVisibleTs = newestTs - timeWindowMs
   firstColumnIdx = max(0, writeIndex - visibleColumnCount)

3. First pass — find maxQty across all visible cells
   For each visible column c:
     For each price level in c:
       if price is within visible Y range:
         maxQty = max(maxQty, c.quantities[i])

4. Second pass — paint liquidity cells
   For each visible column c:
     x = timeToX(c.timestamp(), newestTs, timeWindowMs, panelWidth)
     columnWidth = panelWidth / visibleColumnCount  // typically 1-2px
     For each price level in c where price is in visible Y range:
       y = priceToY(c.prices[i], view)
       qty = c.quantities[i]
       if qty == 0: skip

       // Normalize intensity
       double intensity = (double) qty / maxQty
       intensity *= renderConfig.heatmapIntensity()

       Color cellColor = heatmapColor(intensity, scheme)
       gc.setFill(cellColor)
       gc.fillRect(x, y, max(columnWidth, 1.0), view.rowHeightPx())

5. Third pass — paint volume dots (on top of heatmap)
   For each visible column c:
     x = timeToX(c.timestamp(), newestTs, timeWindowMs, panelWidth)
     For each trade in c.trades():
       dotY = priceToY(trade.price(), view)
       diameter = bubbleDiameter(trade.qty(), instrumentSpec)
       // Full opacity — no decay
       Color fill = trade.aggressorSide() == BUY
           ? scheme.buyBubbleFill : scheme.sellBubbleFill
       gc.setFill(fill)
       gc.fillOval(x - diameter/2, dotY - diameter/2, diameter, diameter)
       if diameter >= 18:
           gc.setFill(scheme.bubbleQtyText)
           gc.fillText(formatQtyShort(trade.qty(), spec), x, dotY)

6. Fourth pass — paint BBO lines (on top of everything)
   if renderConfig.bboLineEnabled():
     gc.setStroke(scheme.bboBidLine)
     gc.setLineWidth(1.0)
     gc.beginPath()
     for each visible column c (oldest to newest):
       x = timeToX(c.timestamp(), ...)
       bidY = priceToY(c.bestBid(), view)
       if first: gc.moveTo(x, bidY)
       else:     gc.lineTo(x, bidY)
     gc.stroke()

     gc.setStroke(scheme.bboAskLine)
     gc.beginPath()
     for each visible column c:
       x = timeToX(c.timestamp(), ...)
       askY = priceToY(c.bestAsk(), view)
       if first: gc.moveTo(x, askY)
       else:     gc.lineTo(x, askY)
     gc.stroke()

7. Draw right-edge divider
   gc.setStroke(scheme.panelDivider)
   gc.strokeLine(panelWidth - 1, 0, panelWidth - 1, panelHeight)
```

### 5.4 Heatmap color mapping

```java
private Color heatmapColor(double intensity, ColorScheme scheme) {
    // intensity: 0.0 = no liquidity, 1.0 = maximum liquidity
    // Interpolate through a 4-stop gradient:
    //   0.0  → heatmapCold    (dark blue/transparent)
    //   0.3  → heatmapCool    (medium blue)
    //   0.6  → heatmapWarm    (yellow)
    //   1.0  → heatmapHot     (bright orange/white)

    intensity = Math.clamp(intensity, 0.0, 1.0);

    if (intensity < 0.01) return Color.TRANSPARENT;

    if (intensity < 0.3) {
        double t = intensity / 0.3;
        return interpolate(scheme.heatmapCold, scheme.heatmapCool, t);
    } else if (intensity < 0.6) {
        double t = (intensity - 0.3) / 0.3;
        return interpolate(scheme.heatmapCool, scheme.heatmapWarm, t);
    } else {
        double t = (intensity - 0.6) / 0.4;
        return interpolate(scheme.heatmapWarm, scheme.heatmapHot, t);
    }
}

private Color interpolate(Color a, Color b, double t) {
    return new Color(
        a.getRed()   + (b.getRed()   - a.getRed())   * t,
        a.getGreen() + (b.getGreen() - a.getGreen()) * t,
        a.getBlue()  + (b.getBlue()  - a.getBlue())  * t,
        a.getOpacity() + (b.getOpacity() - a.getOpacity()) * t
    );
}
```

### 5.5 Column-to-pixel aggregation

When the time window is large relative to panel width, multiple
columns map to a single pixel. The painter must aggregate:

```java
int columnsPerPixel = Math.max(1, visibleColumnCount / (int) panelWidth);
```

When `columnsPerPixel > 1`, the painter groups columns by their pixel
X coordinate and renders the **maximum quantity** at each price level
across the group. This prevents thin liquidity from overwriting thick
liquidity when multiple time slices collapse into one pixel.

### 5.6 Vertical alignment

The heatmap uses the **exact same** `priceToY()` function as the
ladder and volume profile. The `ViewState` record (scroll offset, row
height, canvas height) is shared across all three panes. Scrolling the
ladder scrolls the heatmap. Zooming the ladder zooms the heatmap.
Row iteration steps by `instrumentSpec.tickSize()` per row.

### 5.7 Volume dot sizing

Reuses the same logarithmic formula from SPEC-rendering.md Section 6.2
(BubblePainter). The only differences:
- X position comes from `timeToX()`, not from age-based drift
- Alpha is always 1.0 (no decay)
- The dot is plotted at the column's X position, not drifting

The implementation can call BubblePainter's static sizing method, or
duplicate the formula. Either approach works — the formula is small.

---

## 6. `ColorScheme` additions

```java
// ── Heatmap ────────────────────────────────────────────────────
public final Color heatmapBackground;  // dark, nearly black
public final Color heatmapCold;        // thin liquidity (dark blue)
public final Color heatmapCool;        // moderate liquidity (blue)
public final Color heatmapWarm;        // thick liquidity (yellow)
public final Color heatmapHot;         // extreme liquidity (bright orange)
public final Color bboBidLine;         // best bid trace
public final Color bboAskLine;         // best ask trace
```

### 6.1 Dark theme values
```java
heatmapBackground = Color.web("#0a0a12"),
heatmapCold       = Color.web("#0d1b2a"),   // near-black blue
heatmapCool       = Color.web("#1b4965"),   // steel blue
heatmapWarm       = Color.web("#c2a83e"),   // warm gold
heatmapHot        = Color.web("#f4a261"),   // bright orange
bboBidLine        = Color.web("#1db954"),   // green (same as bid)
bboAskLine        = Color.web("#e63946"),   // red (same as ask)
```

### 6.2 Light theme values
```java
heatmapBackground = Color.web("#e8e8ec"),
heatmapCold       = Color.web("#c5d5e4"),   // pale blue
heatmapCool       = Color.web("#6b9dc2"),   // medium blue
heatmapWarm       = Color.web("#d4a832"),   // warm gold
heatmapHot        = Color.web("#e07820"),   // deep orange
bboBidLine        = Color.web("#1a8a42"),   // darker green for light bg
bboAskLine        = Color.web("#b82d3a"),   // darker red for light bg
```

**Color rationale:** The gradient goes dark blue → blue → yellow →
orange, matching the natural temperature metaphor (cold=empty,
hot=busy). This is the same color language Bookmap uses. Green and
red are reserved for buy/sell — using them in the heatmap gradient
would create confusion with the volume dots.

---

## 7. `RenderConfig` additions

```java
// In RenderConfig:
private volatile boolean heatmapEnabled = true;
private volatile int     heatmapTimeWindowSec = 60;   // seconds of history
private volatile double  heatmapIntensity = 0.7;       // 0.0-1.0 color scaling
private volatile boolean bboLineEnabled = true;

public boolean heatmapEnabled()          { return heatmapEnabled; }
public void setHeatmapEnabled(boolean v) { this.heatmapEnabled = v; }

public int heatmapTimeWindowSec()            { return heatmapTimeWindowSec; }
public void setHeatmapTimeWindowSec(int sec) { this.heatmapTimeWindowSec = sec; }

public double heatmapIntensity()              { return heatmapIntensity; }
public void setHeatmapIntensity(double v)     { this.heatmapIntensity = v; }

public boolean bboLineEnabled()          { return bboLineEnabled; }
public void setBboLineEnabled(boolean v) { this.bboLineEnabled = v; }
```

### 7.1 Time window constraints

| Constraint | Value |
|---|---|
| Minimum | 10 seconds |
| Maximum | 300 seconds (5 minutes) |
| Default | 60 seconds |
| Step | 10 second increments |

**Changing the time window does NOT resize the HeatmapBuffer.** The
buffer is allocated at startup with capacity for the maximum time
window (300 sec = 3020 columns ≈ 98 MB). Changing the time window
only changes how many columns the painter reads — it never changes
the buffer size. This avoids reallocation and data loss.

**Alternative (memory-conscious):** Allocate for the default (60
seconds = 620 columns ≈ 20 MB) and reallocate if the user changes
the window. This saves ~78 MB at default settings but introduces
complexity. **Recommendation:** Allocate for the max (300 sec).
~100 MB on a 1 GB heap is acceptable, and the simplicity benefit
is significant.

### 7.2 Intensity slider constraints

| Constraint | Value |
|---|---|
| Minimum | `0.1` (very faint) |
| Maximum | `1.5` (over-saturated, useful for thin books) |
| Default | `0.7` |
| Step | `0.1` increments |

The intensity value > 1.0 is intentional — it allows the trader to
boost contrast on instruments with thin order books where the default
mapping produces barely visible cells.

---

## 8. UI changes

### 8.1 New LEFT pane — `HeatmapCanvas`

`HeatmapCanvas` is a new `Pane` containing a `Canvas`, placed in the
`BorderPane` LEFT slot. It shares the same `ViewState` as the ladder
and volume profile for vertical alignment.

```java
package com.muralis.ui;

public class HeatmapCanvas extends Pane {
    private final Canvas canvas;
    private final HeatmapPainter heatmapPainter;
    // shares snapshotRef, renderConfig, viewState with LadderCanvas
}
```

The `HeatmapCanvas` does NOT own its own `AnimationTimer`. It is
painted from the SAME `AnimationTimer` that drives the ladder and
volume profile. The frame loop in `LadderCanvas` (or a coordinating
parent) calls all three painters in sequence:

```java
// In AnimationTimer.handle():
heatmapPainter.paint(heatmapGc, snap, viewState, scheme, renderConfig);
ladderPainter.paint(ladderGc, snap, viewState, scheme, renderConfig);
volumeProfilePainter.paint(profileGc, snap, viewState, scheme, renderConfig);
```

### 8.2 Updated MuralisApp layout

```java
// Phase 4 layout:
BorderPane root = new BorderPane();
root.setTop(statusBar);
root.setLeft(heatmapCanvas);      // NEW
root.setCenter(ladderCanvas);
root.setRight(volumeProfilePane);
root.setBottom(controlBar);
```

**Window dimensions:**

| Element | Width | Height |
|---|---|---|
| Full window | 1,580px (default) | 800px |
| `HeatmapCanvas` | ~600px (flexible) | fills LEFT |
| `LadderCanvas` | 700px | fills CENTER |
| `VolumeProfile` | 280px | fills RIGHT |
| `StatusBar` | full width | 28px |
| `ControlBar` | full width | 36px |

Minimum window width: 1,500px. The heatmap pane absorbs window
resize — it flexes from ~400px to ~900px while the ladder and
volume profile stay fixed-width.

### 8.3 ControlBar changes

New controls added:

```java
// Heatmap toggle
CheckBox heatmapToggle = new CheckBox("Heatmap");
heatmapToggle.setSelected(true);
heatmapToggle.selectedProperty().addListener((obs, o, n) ->
    renderConfig.setHeatmapEnabled(n));

// Heatmap intensity slider
Slider heatmapIntensity = new Slider(0.1, 1.5, 0.7);
Label heatmapIntLabel = new Label("70%");

// BBO line toggle
CheckBox bboToggle = new CheckBox("BBO");
bboToggle.setSelected(true);
bboToggle.selectedProperty().addListener((obs, o, n) ->
    renderConfig.setBboLineEnabled(n));

// Time window selector (ComboBox or slider)
ComboBox<String> timeWindow = new ComboBox<>();
timeWindow.getItems().addAll("30s", "60s", "120s", "300s");
timeWindow.setValue("60s");
timeWindow.setOnAction(e -> {
    int sec = Integer.parseInt(
        timeWindow.getValue().replace("s", ""));
    renderConfig.setHeatmapTimeWindowSec(sec);
});
```

### 8.4 Control bar layout (Phase 4)

```
[ ● Connected ] [ ☑ Heatmap ====○=== 70% ] [ ☑ BBO ] [ 60s ▾ ]
[ ☑ Delta ====○==== 50% ] [ Reset Δ ] [ ☑ Vol ] [ Reset Vol ] [ − ] [ + ] [ ◎ ]
```

With the growing number of controls, consider splitting to two rows
or using a collapsible panel. The exact layout is an implementation
detail — the controls and their wiring are what matters.

### 8.5 BubblePainter reactivation

`BubblePainter` is NOT directly reactivated. Instead, its **sizing
formula** (`bubbleDiameter`) is reused by `HeatmapPainter` for volume
dots. The key differences between Phase 1 bubbles and Phase 4 volume
dots:

| | Phase 1 bubbles | Phase 4 volume dots |
|---|---|---|
| Canvas | Right pane (bubble panel) | Left pane (heatmap) |
| X position | Age-based drift | `timeToX()` — fixed at trade time |
| Y position | Same `priceToY()` | Same `priceToY()` |
| Alpha | Decays to 0 over time | Always 1.0 (persistent) |
| Size | Same log formula | Same log formula |
| Color | Same green/red | Same green/red |
| Removal | Fades out after decay | Scrolls off left edge |

The `BubblePainter` class itself can remain disconnected. The sizing
formula is either extracted as a shared utility method or duplicated
(it's 8 lines). The painter code diverges enough (no decay, different
X mapping) that sharing the full paint method isn't practical.

---

## 9. What this does NOT include (deferred)

- **Horizontal time-axis labels** (e.g., "10s ago", "30s ago") — nice
  to have but not essential for Phase 4 launch. Can be added as a
  polish step.
- **Heatmap-specific mouse interaction** (hover to see qty at cell,
  click to freeze time) — Phase 5.
- **Historical replay** — the ring buffer is designed to be
  serializable but serialization is not implemented in Phase 4.
- **Multi-instrument heatmap** — Phase 5. The buffer stores one
  instrument's data. A second instrument needs a second buffer.
- **Aggregated depth** (cumulative depth from BBO outward) — Bookmap
  offers this as an option. Deferred.
- **Iceberg detection** — Phase 5+ when MBO data is available via
  Rithmic.
- **Configurable color gradient** — Phase 5. Fixed 4-stop gradient
  for now.

---

## 10. Implementation sequence

### Step P4.1 — `HeatmapColumn` + `HeatmapBuffer`
- Create `HeatmapColumn.java` record in `com.muralis.engine`
- Create `HeatmapBuffer.java` in `com.muralis.engine`
- Unit tests:
  - Write 5 columns, read them back, verify ordering
  - Write beyond capacity, verify oldest is overwritten
  - Verify `clear()` resets everything
  - Verify `getColumn()` returns null for out-of-range indices

### Step P4.2 — Engine integration
- Add `HeatmapBuffer` field to `OrderBookEngine`
- Add `pendingHeatmapTrades` list
- Add time-based throttle in `runLoop()` (100ms interval)
- Implement `buildHeatmapColumn()`
- Clear on CONNECTING/RECONNECTING
- Modify `buildSnapshot()` to carry `heatmapBuffer` reference
- Update `RenderSnapshot` record (add `heatmapBuffer` field)
- Verify existing tests pass with new RenderSnapshot field

### Step P4.3 — `ColorScheme` + `RenderConfig` additions
- Add 7 heatmap colors to both themes
- Add `heatmapEnabled`, `heatmapTimeWindowSec`, `heatmapIntensity`,
  `bboLineEnabled` to `RenderConfig`

### Step P4.4 — `HeatmapPainter`
- Create `HeatmapPainter.java` in `com.muralis.ui`
- Implement paint sequence from Section 5.3
- Color mapping with 4-stop interpolation
- Volume dot rendering (persistent, no decay)
- BBO line rendering (bid green, ask red)
- `priceToY()` shared with ladder via `ViewState`
- `timeToX()` for horizontal positioning
- Column-to-pixel aggregation when zoomed out

### Step P4.5 — `HeatmapCanvas` + layout
- Create `HeatmapCanvas.java` in `com.muralis.ui`
- Update `MuralisApp` layout to 3-pane (LEFT + CENTER + RIGHT)
- Share `ViewState` across all three panes
- Single `AnimationTimer` drives all three painters
- Update minimum window size

### Step P4.6 — ControlBar controls
- Add heatmap toggle, intensity slider, BBO toggle, time window
  selector
- Wire to `RenderConfig`

### Step P4.7 — Verification
- Run with live data for 5+ minutes
- Verify heatmap shows liquidity bands scrolling left
- Verify bright areas at thick price levels, dark at thin
- Verify volume dots appear at trade time×price coordinates
- Verify BBO lines trace through time
- Verify scroll/zoom synced across all three panes
- Verify heatmap toggle hides/shows
- Verify intensity slider changes color contrast
- Verify time window selector changes visible history
- Verify reconnect clears heatmap and restarts fresh
- Verify no visual alignment drift between panes
- Run for 30 minutes, verify no memory growth beyond expected

---

## 11. Dependency impact

| Package | Changes |
|---|---|
| `model/` | None |
| `provider/` | None |
| `ingestion/` | None |
| `engine/` | New `HeatmapColumn` record. New `HeatmapBuffer` class. Modified `OrderBookEngine` (heatmap column generation, trade collection, reconnect clear). Modified `RenderSnapshot` (new field). Modified `RenderConfig` (4 new fields). |
| `ui/` | New `HeatmapPainter` class. New `HeatmapCanvas` class. Modified `MuralisApp` (3-pane layout). Modified `ColorScheme` (7 colors). Modified `ControlBar` (4 new controls). |
| `Application` | Pass `HeatmapBuffer` to engine constructor. Updated window dimensions. |

No new external dependencies. No new threads. No ingestion changes.
All changes respect ARCHITECTURE.md Section 4 dependency rules.

---

## 12. Invariant checklist

- [ ] `HeatmapBuffer` is written ONLY by the engine thread
- [ ] `HeatmapColumn` is an immutable record (all final fields)
- [ ] `HeatmapBuffer.writeIndex` is `volatile`
- [ ] UI reads `writeIndex` BEFORE reading any column data
- [ ] `HeatmapBuffer.capacity` > visible columns + safety margin
- [ ] `buildHeatmapColumn()` is called at 100ms intervals, NOT per event
- [ ] `pendingHeatmapTrades` is flushed into each column and cleared
- [ ] `heatmapBuffer.clear()` called on CONNECTING and RECONNECTING
- [ ] `pendingHeatmapTrades.clear()` called on reconnect
- [ ] `RenderSnapshot` carries a HeatmapBuffer REFERENCE, not a copy
- [ ] Heatmap painter uses same `priceToY()` as ladder
- [ ] Heatmap painter uses same `tickSize` step for row iteration
- [ ] Volume dots use full opacity (1.0) — no decay
- [ ] Volume dot sizing uses same logarithmic formula as BubblePainter
- [ ] BBO line uses `bestBid` and `bestAsk` from each `HeatmapColumn`
- [ ] Color intensity is relative to visible max, not absolute
- [ ] No double/float stores a price or quantity — long fixed-point only
- [ ] `heatmapTimeWindowSec` max is 300 (enforced by UI)
- [ ] Buffer allocated for max capacity at startup (no runtime resize)
- [ ] No new threads — heatmap column generation runs on engine thread
- [ ] Single `AnimationTimer` drives all three panes

---

*SPEC-phase4-heatmap.md v1.0 — Phase 4 scope locked.*
