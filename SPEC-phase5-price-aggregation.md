# SPEC-phase5-price-aggregation.md — Muralis Phase 5

> This spec defines the Phase 5 feature: price aggregation via
> vertical zoom. When the trader zooms out, adjacent tick levels
> merge into aggregate rows. The ladder, heatmap, volume profile,
> and delta tint all display aggregated values. This is the
> foundational change that Phases 6 and 7 depend on.
>
> This phase also includes refinement of the heatmap color gradient
> from 4-stop to 7-stop as a pre-step.
>
> **Prerequisites:** Phase 4 complete (heatmap with resting liquidity
> cells rendering correctly, BBO lines, volume dots). All tests
> passing.

---

## 1. Feature summary

### What the trader sees

**Zooming out** (Ctrl+scroll down on the ladder) no longer just
shrinks row height. Instead, adjacent tick-level rows merge into
a single display row representing a broader price range:

```
Zoom level 0 (max in):   every 0.01 visible  →  67083.40, 67083.41, 67083.42
Zoom level 1:            10 ticks per row     →  67083.40, 67083.50, 67083.60
Zoom level 2:            100 ticks per row    →  67083, 67084, 67085
Zoom level 3:            1000 ticks per row   →  67080, 67090, 67100
```

At each zoom level:
- **Ladder bid/ask bars** show the SUM of resting qty across all
  ticks in the bucket
- **Heatmap cells** show the MAX resting qty across merged ticks
  (same visual weight principle)
- **Volume profile bars** show the SUM of traded volume across
  merged ticks
- **Delta tint** shows the SUM of net delta across merged ticks
- **Volume dots** snap to the nearest aggregate row center
- **BBO lines** snap to aggregate row boundaries
- Rows in ALL panes align perfectly — heatmap rows always match
  ladder rows at all zoom levels

**Zooming in** (Ctrl+scroll up) reduces the aggregation, revealing
more granular tick levels until the native tick size is reached.

### What does NOT change
- Engine data structures — aggregation is at PAINT TIME only
- RenderSnapshot fields
- Ingestion layer
- Thread model
- HeatmapBuffer or its column format

### Also included: heatmap color gradient refinement

The heatmap color gradient is upgraded from 4-stop to 7-stop:

```
Current (Phase 4):   dark blue → blue → yellow → orange
New (Phase 5):       black → dark blue → blue → white → yellow → orange → red
```

This is implemented as a pre-step before the aggregation work
begins, since it affects only `HeatmapPainter.heatmapColor()` and
`ColorScheme`.

---

## 2. Core concept — `ticksPerRow` and `effectiveTickSize`

### 2.1 New fields in ViewState

```java
// Added to ViewState (or LadderView):
int  ticksPerRow;         // how many native ticks per display row
long effectiveTickSize;   // = instrumentSpec.tickSize() * ticksPerRow
```

`ticksPerRow = 1` means max zoom — every native tick is its own row.
`ticksPerRow = 10` means 10 native ticks merge into one row.

`effectiveTickSize` is derived: it is always
`instrumentSpec.tickSize() * ticksPerRow`. It is never stored
independently — it is computed from `ticksPerRow` whenever
`ViewState` is constructed.

### 2.2 Aggregation level sequences

The `ticksPerRow` values follow a "nice numbers" sequence so that
price labels are always clean round numbers. The sequence is
instrument-specific and derived from `tickSize` and `priceScale`.

**For BTCUSDT** (`tickSize=1L`, `priceScale=2`, native tick = 0.01):

| Level | ticksPerRow | effectiveTickSize | Price step | Label example |
|---|---|---|---|---|
| 0 | 1 | 1 | 0.01 | 67,083.40 |
| 1 | 5 | 5 | 0.05 | 67,083.40 |
| 2 | 10 | 10 | 0.10 | 67,083.40 |
| 3 | 50 | 50 | 0.50 | 67,083.50 |
| 4 | 100 | 100 | 1.00 | 67,083 |
| 5 | 500 | 500 | 5.00 | 67,085 |
| 6 | 1000 | 1000 | 10.00 | 67,080 |
| 7 | 5000 | 5000 | 50.00 | 67,050 |
| 8 | 10000 | 10000 | 100.00 | 67,000 |

**For CME ES** (`tickSize=25L`, `priceScale=2`, native tick = 0.25):

| Level | ticksPerRow | effectiveTickSize | Price step | Label example |
|---|---|---|---|---|
| 0 | 1 | 25 | 0.25 | 6,445.25 |
| 1 | 4 | 100 | 1.00 | 6,445 |
| 2 | 20 | 500 | 5.00 | 6,445 |
| 3 | 40 | 1000 | 10.00 | 6,440 |
| 4 | 100 | 2500 | 25.00 | 6,425 |
| 5 | 200 | 5000 | 50.00 | 6,400 |
| 6 | 400 | 10000 | 100.00 | 6,400 |

### 2.3 Deriving the sequence programmatically

The sequence should be computed from InstrumentSpec, not hardcoded
per instrument. Algorithm:

```java
static int[] computeAggregationLevels(InstrumentSpec spec) {
    // Start with ticksPerRow = 1 (native tick)
    // Multiply by factors that produce round display numbers
    // The factors depend on the relationship between tickSize
    // and priceScale

    long tick = spec.tickSize();
    int scale = spec.priceScale();
    long one = (long) Math.pow(10, scale);  // 1.00 in fixed-point

    List<Integer> levels = new ArrayList<>();
    levels.add(1);  // native tick

    // Generate levels that are multiples producing round numbers
    // at progressively larger intervals
    long[] targets = {
        one / 20,    // 0.05 for scale=2
        one / 10,    // 0.10
        one / 2,     // 0.50
        one,         // 1.00
        one * 5,     // 5.00
        one * 10,    // 10.00
        one * 50,    // 50.00
        one * 100,   // 100.00
        one * 500,   // 500.00
        one * 1000,  // 1000.00
    };

    for (long target : targets) {
        if (target > tick) {
            int tpr = (int)(target / tick);
            if (tpr > 1 && tpr != levels.get(levels.size() - 1)) {
                levels.add(tpr);
            }
        }
    }

    return levels.stream().mapToInt(Integer::intValue).toArray();
}
```

The result is stored once when InstrumentSpec is resolved and
passed to ViewState as `int[] aggregationLevels`.

### 2.4 Current aggregation level index

```java
// On LadderCanvas (UI state):
private int aggregationLevelIndex = 0;  // 0 = max zoom (native tick)
```

Zoom in decrements toward 0. Zoom out increments toward
`aggregationLevels.length - 1`.

---

## 3. Zoom behavior change

### 3.1 Current behavior (Phase 1-4)

Ctrl+scroll continuously adjusts `rowHeightPx` between
`MIN_ROW_PX` (10) and `MAX_ROW_PX` (60). Each tick is always one
row.

### 3.2 New behavior (Phase 5)

Ctrl+scroll changes the **aggregation level** through discrete
steps. `rowHeightPx` auto-adjusts within a comfortable range
when the level changes.

```java
canvas.setOnScroll(event -> {
    if (event.isControlDown()) {
        if (event.getDeltaY() > 0) {
            // Zoom IN — decrease aggregation (more detail)
            if (aggregationLevelIndex > 0) {
                aggregationLevelIndex--;
                rowHeightPx = DEFAULT_ROW_PX;  // reset to default
            }
        } else {
            // Zoom OUT — increase aggregation (less detail)
            if (aggregationLevelIndex < aggregationLevels.length - 1) {
                aggregationLevelIndex++;
                rowHeightPx = DEFAULT_ROW_PX;  // reset to default
            }
        }
        event.consume();
    } else {
        // Scroll without Ctrl → vertical pan (unchanged)
        scrollOffsetPx += (long)(event.getDeltaY() * -1.5);
        userScrolled = true;
        event.consume();
    }
});
```

| Constant | Value |
|---|---|
| `DEFAULT_ROW_PX` | `20.0` |

When the aggregation level changes, `rowHeightPx` resets to the
default. The trader can still fine-tune row height within a level
via keyboard `+`/`-` (which adjust `rowHeightPx` without changing
the aggregation level).

### 3.3 ViewState — updated record definition

The ViewState record (originally defined in SPEC-rendering.md Section
4.2 with 5 fields) gains 2 new fields. The full record after Phase 5:

```java
private record ViewState(
    double  rowHeightPx,        // pixel height of each display row
    long    scrollOffsetPx,     // vertical scroll offset
    boolean userScrolled,       // true if user overrode auto-centre
    double  canvasWidth,        // panel width in pixels
    double  canvasHeight,       // panel height in pixels
    int     ticksPerRow,        // aggregation level (1 = native tick)
    long    effectiveTickSize   // = tickSize * ticksPerRow
) {}
```

Construction:

```java
ViewState viewState() {
    int ticksPerRow = aggregationLevels[aggregationLevelIndex];
    long effectiveTickSize = instrumentSpec.tickSize() * ticksPerRow;
    return new ViewState(
        rowHeightPx,
        scrollOffsetPx,
        userScrolled,
        canvas.getWidth(),
        canvas.getHeight(),
        ticksPerRow,
        effectiveTickSize
    );
}
```

### 3.4 Auto-centering update

The auto-centering formula from SPEC-rendering.md Section 4.4 must
use `effectiveTickSize` for the row index calculation:

```java
// Updated auto-centre (when userScrolled == false):
long midPrice = (snap.bestBid() + snap.bestAsk()) / 2;
long midBucket = bucketPrice(midPrice, effectiveTickSize);
long centreRowIndex = (long)((canvasHeight / 2.0) / rowHeightPx);
scrollOffsetPx = (midBucket / effectiveTickSize - centreRowIndex)
                 * (long) rowHeightPx;
```

When `ticksPerRow == 1`, this is identical to the Phase 1 formula.

---

## 4. Price bucketing — the shared aggregation function

### 4.1 Bucket boundary

```java
// Round a price DOWN to its bucket boundary
static long bucketPrice(long price, long effectiveTickSize) {
    return (price / effectiveTickSize) * effectiveTickSize;
}
```

For `effectiveTickSize = 100` (i.e., 1.00 for priceScale=2):
```
67083_40 → 67083_00
67083_99 → 67083_00
67084_00 → 67084_00
```

### 4.2 Bucket quantity sum (for ladder and volume profile)

```java
// Sum quantities for all prices in a bucket from sorted arrays
static long bucketQtySum(long bucketStart, long effectiveTickSize,
                         long[] prices, long[] qtys) {
    long sum = 0L;
    long bucketEnd = bucketStart + effectiveTickSize;
    for (int i = 0; i < prices.length; i++) {
        if (prices[i] >= bucketStart && prices[i] < bucketEnd) {
            sum += qtys[i];
        }
    }
    return sum;
}
```

### 4.3 Bucket quantity max (for heatmap cells)

```java
// Max quantity for all prices in a bucket (heatmap intensity)
static long bucketQtyMax(long bucketStart, long effectiveTickSize,
                         long[] prices, long[] qtys) {
    long max = 0L;
    long bucketEnd = bucketStart + effectiveTickSize;
    for (int i = 0; i < prices.length; i++) {
        if (prices[i] >= bucketStart && prices[i] < bucketEnd) {
            max = Math.max(max, qtys[i]);
        }
    }
    return max;
}
```

### 4.4 Bucket delta sum (for delta tint)

```java
// Sum deltas for all prices in a bucket
static long bucketDeltaSum(long bucketStart, long effectiveTickSize,
                           Map<Long, Long> priceDeltaMap,
                           long tickSize) {
    long sum = 0L;
    for (long p = bucketStart; p < bucketStart + effectiveTickSize;
         p += tickSize) {
        Long delta = priceDeltaMap.get(p);
        if (delta != null) sum += delta;
    }
    return sum;
}
```

### 4.5 Bucket volume sum (for volume profile)

Same as bucketDeltaSum but over `priceVolumeMap`:

```java
static long bucketVolumeSum(long bucketStart, long effectiveTickSize,
                            Map<Long, Long> priceVolumeMap,
                            long tickSize) {
    long sum = 0L;
    for (long p = bucketStart; p < bucketStart + effectiveTickSize;
         p += tickSize) {
        Long vol = priceVolumeMap.get(p);
        if (vol != null) sum += vol;
    }
    return sum;
}
```

### 4.6 Shared utility class

All bucket functions live in a shared utility:

```java
package com.muralis.ui;

public final class PriceAggregation {
    private PriceAggregation() {}  // utility class

    public static long bucketPrice(long price, long effectiveTickSize) { ... }
    public static long bucketQtySum(...) { ... }
    public static long bucketQtyMax(...) { ... }
    public static long bucketDeltaSum(...) { ... }
    public static long bucketVolumeSum(...) { ... }
    public static int[] computeAggregationLevels(InstrumentSpec spec) { ... }
}
```

This class lives in `com.muralis.ui` because aggregation is a
PAINT-TIME concept. The engine never aggregates.

### 4.7 Performance consideration

When `ticksPerRow = 1`, no aggregation is needed — the painters
use raw tick-level data directly. The bucket functions are only
called when `ticksPerRow > 1`. Each painter should check
`ticksPerRow == 1` as a fast path:

```java
if (view.ticksPerRow() == 1) {
    // Use prices[i] directly — no bucketing
} else {
    // Use PriceAggregation.bucketQtySum(...)
}
```

---

## 5. Painter changes

### 5.1 Row iteration (all painters)

**Before Phase 5:**
```java
for (long price = topVisiblePrice; price >= bottomVisiblePrice;
     price -= tickSize) {
    // paint row
}
```

**After Phase 5:**
```java
long ets = view.effectiveTickSize();
long topBucket = PriceAggregation.bucketPrice(topVisiblePrice, ets);
for (long bucket = topBucket; bucket >= bottomVisiblePrice;
     bucket -= ets) {
    // paint row — bucket represents the price range
    //   [bucket, bucket + ets)
}
```

The `priceToY()` function changes to use `effectiveTickSize`:

```java
static double rowY(long price, long referencePrice,
                   long effectiveTickSize,
                   double rowHeightPx, long scrollOffsetPx) {
    long rowIndex = (referencePrice - price) / effectiveTickSize;
    return rowIndex * rowHeightPx - scrollOffsetPx;
}
```

### 5.2 LadderPainter changes

- Row iteration steps by `effectiveTickSize`
- For each row at bucket `b`:
  - Bid qty = `bucketQtySum(b, ets, bidPrices, bidQtys)`
  - Ask qty = `bucketQtySum(b, ets, askPrices, askQtys)`
- Bar width proportional to aggregated qty
- Price label formatting: when `ticksPerRow > 1` and the bucket
  spans a whole number, show fewer decimal places. E.g., at
  `effectiveTickSize=100` (1.00 step): show `"67,083"` not
  `"67,083.00"`.
- Delta tint: `bucketDeltaSum(b, ets, priceDeltaMap, tickSize)`

**Spread collapse edge case:** When `effectiveTickSize` is large
enough that `bucketPrice(bestBid) == bucketPrice(bestAsk)`, the
spread collapses into a single row. This is correct Bookmap
behavior — at far zoom levels, the bid/ask distinction is not
meaningful at per-row granularity. The painter should:
- Show the row as the best bid highlight (green) if the bucket
  contains the best bid
- Show both bid and ask bars on the same row (bid left, ask right)
- The spread highlight region (rows between best bid and best ask)
  may be zero rows wide — this is handled by the existing "no
  spread rows to paint" path

**Price label formatting logic:**
```java
String formatBucketPrice(long bucketPrice, InstrumentSpec spec,
                         long effectiveTickSize) {
    BigDecimal bd = BigDecimal.valueOf(bucketPrice, spec.priceScale());
    // If effectiveTickSize produces whole-number steps,
    // strip trailing zeros for cleaner labels
    return bd.stripTrailingZeros().toPlainString();
}
```

### 5.3 HeatmapPainter changes

- Vertical cell painting steps by `effectiveTickSize`
- For each cell at (column, bucket):
  - qty = `bucketQtyMax(b, ets, col.prices(), col.quantities())`
  - Color = `heatmapColor(qty / maxQty)`
- Cell height = `rowHeightPx` (matches ladder row height)
- Volume dot Y = `rowY(bucketPrice(trade.price(), ets), ...)`
  — dot snaps to aggregate row center
- BBO line Y = `rowY(bucketPrice(col.bestBid(), ets), ...)`

### 5.4 VolumeProfilePainter changes

- Row iteration steps by `effectiveTickSize`
- For each row at bucket `b`:
  - volume = `bucketVolumeSum(b, ets, priceVolumeMap, tickSize)`
- Bar width proportional to aggregated volume

### 5.5 Performance at high aggregation levels

When `ticksPerRow = 1000`, a bucket spans 1000 native ticks.
The `bucketQtySum` function iterates all prices in the snapshot
arrays for each bucket. With ~1000 price levels and ~30 visible
rows, this is 30 × 1000 = 30,000 array element comparisons per
frame — trivial at 60 FPS.

For the heatmap, the same analysis applies per visible column.
With ~300 visible columns × 30 visible rows × 1000 levels:
9 million comparisons. This could be slow.

**Optimization for heatmap:** Pre-build a per-column bucket map
once per frame, then look up buckets directly:

```java
// Once per column:
Map<Long, Long> bucketMax = new HashMap<>();
for (int i = 0; i < col.prices().length; i++) {
    long bucket = bucketPrice(col.prices()[i], ets);
    bucketMax.merge(bucket, col.quantities()[i], Math::max);
}
// Then for each visible row:
Long qty = bucketMax.getOrDefault(bucket, 0L);
```

This reduces heatmap aggregation to O(levels) per column instead
of O(levels × visibleRows).

---

## 6. Heatmap color gradient refinement (pre-step)

### 6.1 New 7-stop gradient

```java
private Color heatmapColor(double intensity, ColorScheme scheme) {
    intensity = Math.clamp(intensity, 0.0, 1.0);

    if (intensity < 0.005) return Color.TRANSPARENT;

    // 7-stop gradient:
    // 0.00 → heatmapBlack     (near-black)
    // 0.10 → heatmapDarkBlue  (dark blue)
    // 0.25 → heatmapBlue      (blue)
    // 0.40 → heatmapWhite     (white/bright)
    // 0.55 → heatmapYellow    (yellow)
    // 0.75 → heatmapOrange    (orange)
    // 1.00 → heatmapRed       (red/dark red)

    double[][] stops = {
        {0.00}, {0.10}, {0.25}, {0.40}, {0.55}, {0.75}, {1.00}
    };
    Color[] colors = {
        scheme.heatmapBlack, scheme.heatmapDarkBlue,
        scheme.heatmapBlue, scheme.heatmapWhite,
        scheme.heatmapYellow, scheme.heatmapOrange,
        scheme.heatmapRed
    };

    for (int i = 0; i < stops.length - 1; i++) {
        if (intensity <= stops[i + 1][0]) {
            double t = (intensity - stops[i][0])
                     / (stops[i + 1][0] - stops[i][0]);
            return interpolate(colors[i], colors[i + 1], t);
        }
    }
    return colors[colors.length - 1];
}
```

### 6.2 New ColorScheme fields

```java
// Replace existing 4 heatmap colors with 7:
public final Color heatmapBlack;      // near-black (empty/minimal)
public final Color heatmapDarkBlue;   // thin liquidity
public final Color heatmapBlue;       // moderate liquidity
public final Color heatmapWhite;      // medium liquidity
public final Color heatmapYellow;     // thick liquidity
public final Color heatmapOrange;     // very thick
public final Color heatmapRed;        // extreme (walls)
```

### 6.3 Dark theme values
```java
heatmapBlack    = Color.web("#050510"),
heatmapDarkBlue = Color.web("#0d1b2a"),
heatmapBlue     = Color.web("#1b4965"),
heatmapWhite    = Color.web("#d4dbe0"),
heatmapYellow   = Color.web("#c2a83e"),
heatmapOrange   = Color.web("#e07820"),
heatmapRed      = Color.web("#8b1a1a"),
```

### 6.4 Light theme values
```java
heatmapBlack    = Color.web("#e0e0e4"),
heatmapDarkBlue = Color.web("#c0cfdd"),
heatmapBlue     = Color.web("#6b9dc2"),
heatmapWhite    = Color.web("#f5f5f7"),
heatmapYellow   = Color.web("#d4a832"),
heatmapOrange   = Color.web("#e07820"),
heatmapRed      = Color.web("#8b1a1a"),
```

### 6.5 Migration from Phase 4 color fields

The Phase 4 gradient fields (`heatmapCold`, `heatmapCool`,
`heatmapWarm`, `heatmapHot`) are **removed** and replaced by the
7 new gradient fields above.

The following Phase 4 color fields **survive unchanged:**
- `heatmapBackground` — panel background fill (near-black)
- `bboBidLine` — best bid trace color (green)
- `bboAskLine` — best ask trace color (red)

---

## 7. What does NOT change

- `OrderBookEngine` — no modifications
- `HeatmapBuffer` / `HeatmapColumn` — no modifications
- `DeltaAccumulator` / `VolumeAccumulator` — no modifications
- `RenderSnapshot` — no new fields (ticksPerRow lives in ViewState)
- Ingestion layer — no modifications
- Thread model — still 3 threads
- No new dependencies

Aggregation is a **pure UI concept**. The engine stores raw tick
data. The UI interprets it at the current zoom level. This means
changing the zoom level is instant — no re-processing.

---

## 8. Implementation sequence

### Step P5.0 — Color gradient refinement (pre-step)
- Replace 4 heatmap color fields with 7 in ColorScheme
- Update heatmapColor() in HeatmapPainter to 7-stop gradient
- Verify heatmap colors match the Bookmap reference:
  black for empty, dark blue → blue → white → yellow → orange → red
- Remove old heatmapCold/Cool/Warm/Hot fields

**Verification gate:** `.\gradlew.bat run` — heatmap shows rich
color range. Empty areas are near-black. Thick levels show
warm/orange. Extreme levels show red.

### Step P5.1 — PriceAggregation utility class
- Create `PriceAggregation.java` in `com.muralis.ui`
- Implement all bucket functions from Section 4
- Implement `computeAggregationLevels(InstrumentSpec)`
- Unit tests:
  - bucketPrice with various effectiveTickSize values
  - bucketQtySum with sorted price arrays
  - computeAggregationLevels for BTCUSDT and ES specs
  - Verify bucket boundaries are always round numbers

**Verification gate:** `.\gradlew.bat test` — all tests pass.

### Step P5.2 — ViewState extension
- Add `ticksPerRow` and `effectiveTickSize` to ViewState
- Add `aggregationLevelIndex` and `aggregationLevels[]` to
  LadderCanvas
- Modify ViewState construction to include new fields
- Default: `aggregationLevelIndex = 0` (native tick)
- Verify existing behavior unchanged (ticksPerRow=1 is identity)

**Verification gate:** `.\gradlew.bat run` — everything looks
exactly the same as before (ticksPerRow=1 is the default).

### Step P5.3 — Zoom behavior change
- Modify Ctrl+scroll handler to cycle aggregation levels
  instead of continuously adjusting rowHeightPx
- Keep `+`/`-` keyboard for fine row height adjustment
- Reset rowHeightPx to DEFAULT_ROW_PX on level change

**Verification gate:** `.\gradlew.bat run` — Ctrl+scroll now
cycles through discrete zoom levels. At level 0 (default),
behavior is identical to Phase 4. Ctrl+scroll out shows merged
price labels. Ctrl+scroll in returns to native ticks.

### Step P5.4 — LadderPainter aggregation
- Modify row iteration to use effectiveTickSize
- Add bucket-sum for bid/ask quantities
- Add bucket-sum for delta tint
- Price label formatting for aggregated rows
- priceToY() uses effectiveTickSize

**Verification gate:** `.\gradlew.bat run` — zoom out on ladder
shows merged rows with summed quantities. Price labels are clean
round numbers. Delta tint shows aggregated delta.

### Step P5.5 — HeatmapPainter aggregation
- Modify vertical cell painting to use effectiveTickSize
- Per-column bucket map optimization (Section 5.5)
- Volume dot Y-snap to aggregate row center
- BBO line Y-snap to aggregate row boundaries
- Cell height matches ladder row height at all zoom levels

**Verification gate:** `.\gradlew.bat run` — zoom out, verify
heatmap rows match ladder rows exactly. No vertical misalignment
between panes. Volume dots snap correctly. BBO lines snap.

### Step P5.6 — VolumeProfilePainter aggregation
- Modify row iteration to use effectiveTickSize
- Bucket-sum traded volume

**Verification gate:** `.\gradlew.bat run` — zoom out, verify
volume profile bars align with ladder rows.

### Step P5.7 — Comprehensive verification
- Test all zoom levels for BTCUSDT
- Verify price labels are correct at each level
- Verify quantities sum correctly (spot-check: sum of aggregated
  row should equal sum of individual ticks)
- Verify no alignment drift between any pair of panes
- Verify zoom in returns to native tick display
- Run for 10+ minutes at various zoom levels
- Check for visual glitches at aggregation boundaries

---

## 9. What this does NOT include (deferred)

- **Horizontal heatmap zoom** — Phase 6
- **Click-and-drag history navigation** — Phase 6
- **Return-to-live caret button** — Phase 6
- **COB numbers on ladder** — Phase 7
- **SVP buy/sell split** — Phase 7
- **Per-row fine zoom** (adjusting rowHeightPx within a level) is
  supported via keyboard `+`/`-` but not specced as a feature

---

## 10. Dependency impact

| Package | Changes |
|---|---|
| `model/` | None |
| `provider/` | None |
| `ingestion/` | None |
| `engine/` | None |
| `ui/` | New `PriceAggregation` utility class. Modified `ViewState` (2 new fields). Modified `LadderCanvas` (aggregation level state + zoom handler). Modified `LadderPainter` (bucket iteration + aggregated quantities). Modified `HeatmapPainter` (bucket iteration + per-column bucket map + dot/BBO snap). Modified `VolumeProfilePainter` (bucket iteration). Modified `ColorScheme` (7 heatmap colors replacing 4). |
| `Application` | Compute and pass aggregation levels from InstrumentSpec. |

No new external dependencies. No new threads. No engine changes.

---

## 11. Invariant checklist

- [ ] `ticksPerRow` is always ≥ 1
- [ ] `effectiveTickSize` is always > 0 (derived: `tickSize × ticksPerRow`)
- [ ] `effectiveTickSize` = `tickSize × ticksPerRow` (computed, never stored independently)
- [ ] `aggregationLevels[0]` is always 1 (native tick)
- [ ] `bucketPrice()` always returns a value that is a multiple of `effectiveTickSize`
- [ ] All painters use `effectiveTickSize` for row iteration, not `tickSize` directly
- [ ] All painters share the same ViewState (same ticksPerRow for all panes)
- [ ] When `ticksPerRow == 1`, behavior is identical to Phase 4 (no regression)
- [ ] Ladder bid/ask use SUM aggregation
- [ ] Heatmap cells use MAX aggregation
- [ ] Volume profile uses SUM aggregation
- [ ] Delta tint uses SUM aggregation
- [ ] Volume dots snap to `bucketPrice(trade.price(), ets)` row center
- [ ] BBO lines snap to `bucketPrice(bestBid/bestAsk, ets)` row
- [ ] No engine data structures are modified
- [ ] No `double` or `float` stores a price or quantity
- [ ] Price labels show appropriate decimal precision for the zoom level
- [ ] Heatmap color gradient has 7 stops (black→darkBlue→blue→white→yellow→orange→red)
- [ ] Heatmap per-column bucket map optimization is used when `ticksPerRow > 1`
- [ ] `PriceAggregation` utility is in `com.muralis.ui` (paint-time only)
- [ ] Ctrl+scroll cycles aggregation levels discretely (not continuous)

---

*SPEC-phase5-price-aggregation.md v1.0 — Phase 5 scope locked.*
