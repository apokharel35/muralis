# SPEC-phase3-volume-profile.md — Muralis Phase 3

> This spec defines the Phase 3 feature: a session volume profile panel
> that replaces the bubble drift panel on the right side of the ladder.
> Each price level that has traded shows a horizontal bar whose width
> represents the total volume traded at that price since session start
> (or last manual reset).
>
> **Prerequisites:** Phase 2 complete (delta tint with toggle, slider,
> reset). All unit tests passing.

---

## 1. Feature summary

### What the trader sees
The right panel (previously showing drifting trade bubbles) now shows
a **volume profile** — horizontal bars at each price level where trades
have occurred during the session. Wider bars = more volume at that price.

- **Single color** — a neutral accent color (teal/cyan), not buy/sell
  split. The delta tint on the ladder already shows buy vs sell
  directional information; the volume profile shows **total activity
  regardless of direction**.
- **Bar width** is proportional to volume at that price relative to the
  maximum volume across all visible levels
- **Qty labels** appear on bars that exceed a minimum width threshold
- **Toggle on/off** and **manual reset** — same pattern as delta tint
- Bars align vertically with the ladder — same price-to-Y mapping

### What the trader learns
The volume profile answers: "Where did the most trading happen?"
High-volume price levels are areas of interest — they often act as
support/resistance because traders have established positions there.
Combined with the delta tint (which shows *who* was aggressive), the
volume profile shows *how much* activity occurred at each level.

### What changes from Phase 2
- Right pane switches from `BubblePainter` to `VolumeProfilePainter`
- `BubblePainter` remains in the codebase but is disconnected from
  the `AnimationTimer` — it will be reused in Phase 4 as volume dots
  on the scrolling heatmap
- `TradeBuffer` continues to accumulate blips (engine still calls
  `applyTrade`) but `recentTrades` in `RenderSnapshot` is no longer
  rendered. The field stays in the record for Phase 4.
- The decay slider in the control bar becomes inactive (no bubbles to
  decay). It can be hidden or disabled — implementation choice.

### What does NOT change
- The DOM ladder (bid/ask bars, price text, spread highlight)
- The delta tint overlay (Phase 2)
- The engine thread model, queue, and snapshot pattern
- The ingestion layer
- No new threads, no new dependencies

---

## 2. Data model — `VolumeAccumulator`

A new class in `com.muralis.engine` that accumulates total traded
volume per price level from every `NormalizedTrade`.

```java
package com.muralis.engine;

public class VolumeAccumulator {

    // key = price (fixed-point long), value = total volume (fixed-point long)
    private final HashMap<Long, Long> priceVolumes;

    public void accumulate(long price, long qty) {
        priceVolumes.merge(price, qty, Long::sum);
    }

    public long getVolume(long price) {
        return priceVolumes.getOrDefault(price, 0L);
    }

    public long getMaxVolume() {
        long max = 0L;
        for (long vol : priceVolumes.values()) {
            max = Math.max(max, vol);
        }
        return max;
    }

    public Map<Long, Long> getSnapshot() {
        return Map.copyOf(priceVolumes);
    }

    public void clear() {
        priceVolumes.clear();
    }
}
```

### 2.1 Difference from `DeltaAccumulator`

| | DeltaAccumulator | VolumeAccumulator |
|---|---|---|
| Value stored | `long[2]` (buyVol, sellVol) | `long` (total vol) |
| Accumulation | Splits by AggressorSide | Sums both sides |
| Snapshot field | `priceDeltaMap` (delta = buy - sell) | `priceVolumeMap` (total) |
| Normalization | Against `maxAbsDelta` | Against `maxVolume` |

`VolumeAccumulator` is deliberately simpler than `DeltaAccumulator`
because it doesn't need side splitting. Buy and sell volume both
contribute equally to the profile.

### 2.2 Ownership and thread safety
`VolumeAccumulator` is owned exclusively by the engine thread. No
synchronization needed. Same ownership model as `DeltaAccumulator`,
`OrderBook`, and `TradeBuffer`.

### 2.3 Memory behavior
Each entry is a `Long` key + `Long` value ≈ 32 bytes. At 2,000
traded price levels per session:
```
2,000 levels × 32 bytes ≈ 64 KB
```
Negligible. No eviction needed.

---

## 3. Engine changes — `OrderBookEngine`

### 3.1 New field
```java
private final VolumeAccumulator volumeAccumulator = new VolumeAccumulator();
```

### 3.2 `applyTrade` modification
After the existing duplicate check, `TradeBlip` creation, and
`deltaAccumulator.accumulate()`, add:

```java
volumeAccumulator.accumulate(t.price(), t.qty());
```

Note: `AggressorSide` is NOT passed — volume profile sums both sides.

### 3.3 `applyConnectionEvent` modification
On `CONNECTING` and `RECONNECTING`, clear the accumulator:

```java
volumeAccumulator.clear();
```

### 3.4 `buildSnapshot` modification
Add volume data to `RenderSnapshot`. See Section 4.

### 3.5 Reset support
Same volatile-boolean pattern as delta reset:

```java
// In OrderBookEngine:
private volatile boolean volumeResetRequested = false;

public void requestVolumeReset() {
    volumeResetRequested = true;
}

// In runLoop, before buildSnapshot:
if (volumeResetRequested) {
    volumeAccumulator.clear();
    volumeResetRequested = false;
}
```

---

## 4. `RenderSnapshot` changes

Add two new fields:

```java
public record RenderSnapshot(
    // ... existing fields (including priceDeltaMap, maxAbsDelta) ...
    Map<Long, Long> priceVolumeMap,  // price → total traded volume
    long            maxVolume        // largest volume across all levels
) {}
```

### 4.1 Construction in `buildSnapshot()`

```java
Map<Long, Long> priceVolumeMap = volumeAccumulator.getSnapshot();  // Map.copyOf inside
long maxVolume = volumeAccumulator.getMaxVolume();
```

`priceVolumeMap` is `Map.copyOf()` — immutable. Same pattern as
`priceDeltaMap` from Phase 2.

---

## 5. New class — `VolumeProfilePainter`

```java
package com.muralis.ui;

public class VolumeProfilePainter {

    public void paint(
        GraphicsContext  gc,
        RenderSnapshot   snap,
        ViewState        view,
        ColorScheme      scheme,
        RenderConfig     renderConfig
    )
}
```

`VolumeProfilePainter` is a stateless utility class. Same pattern as
`LadderPainter` and `BubblePainter`.

### 5.1 Panel layout

The volume profile panel occupies the same 280px RIGHT slot that the
bubble panel previously occupied. The panel shares the same vertical
price-to-Y mapping as the ladder — a bar at price `p` appears at the
same Y coordinate as price `p` on the ladder.

```
panelWidth = 280px

Bars grow LEFT from the right edge of the panel:
|                      ████████|  ← high volume
|                ██████████████|  ← highest volume (widest bar)
|                          ████|  ← moderate volume
|                              |  ← no volume at this price
```

Bars grow leftward (anchored at the right edge) so the visual weight
accumulates toward the ladder, creating a natural connection between
the price levels and their traded volume.

### 5.2 Bar width normalization — linear

```java
double barWidth(long volume, long maxVolume, double panelWidth) {
    if (maxVolume == 0L) return 0.0;
    double ratio = (double) volume / maxVolume;
    return ratio * (panelWidth - LABEL_MARGIN);
}
```

| Constant | Value | Meaning |
|---|---|---|
| `LABEL_MARGIN` | `8.0` | Right-side margin for visual breathing room |

**Why linear, not logarithmic:** Unlike bubble sizing where the range
spans 4 orders of magnitude (0.001 BTC to 10 BTC), volume profile
bars represent similar instruments. The max-volume level gets the
full bar width; others are proportional. Linear normalization gives
the trader an accurate visual ratio of volume between levels.
Logarithmic would compress the range and reduce contrast between
moderately-traded and heavily-traded levels.

### 5.3 Paint sequence (per frame)

```
1. Check renderConfig.volumeProfileEnabled()
   If false → fill panel background, draw divider, return

2. Fill panel background
   gc.setFill(scheme.panelBackground)
   gc.fillRect(0, 0, panelWidth, panelHeight)

3. Draw panel divider
   gc.setStroke(scheme.panelDivider)
   gc.setLineWidth(1.0)
   gc.strokeLine(0, 0, 0, panelHeight)

4. For each visible price level p (same iteration as ladder):
   volume = snap.priceVolumeMap().getOrDefault(p, 0L)
   if volume == 0L: skip

   maxVol = snap.maxVolume()
   if maxVol == 0L: skip

   barW = barWidth(volume, maxVol, panelWidth)
   barX = panelWidth - LABEL_MARGIN - barW   // grows LEFT from right edge
   barY = rowY(p, view) + 1                   // 1px top inset
   barH = view.rowHeightPx() - 2              // 1px gap top/bottom

   gc.setFill(scheme.volumeBar)
   gc.fillRect(barX, barY, barW, barH)

5. Paint qty labels on bars wider than MIN_LABEL_WIDTH:
   if barW >= MIN_LABEL_WIDTH:
       displayVol = formatVolume(volume, instrumentSpec)
       gc.setFill(scheme.volumeBarText)
       gc.setFont(volumeFont)
       gc.setTextAlign(RIGHT)
       gc.setTextBaseline(VPos.CENTER)
       gc.fillText(displayVol, barX - 4, rowCentreY(p, view))
```

| Constant | Value | Meaning |
|---|---|---|
| `MIN_LABEL_WIDTH` | `40.0` | Minimum bar width to show qty label |

### 5.4 Volume formatting

```java
private String formatVolume(long volume, InstrumentSpec spec) {
    BigDecimal bd = BigDecimal.valueOf(volume, spec.qtyScale());
    // Compact display: show up to 4 significant figures
    // 1234L with qtyScale=3 → "1.234"
    // 41000L with qtyScale=3 → "41"
    // 500L with qtyScale=3 → "0.5"
    return bd.stripTrailingZeros().toPlainString();
}
```

Same `formatQty` pattern from SPEC-rendering.md Section 5.7.

### 5.5 Visible price level alignment

The volume profile painter uses the **exact same** price-to-Y
conversion as the ladder painter. The `ViewState` record (scroll
offset, row height, canvas height) is shared between both painters
in the `AnimationTimer` frame loop. This ensures:

- A bar at price 67,083.40 appears at the same Y as the ladder row
  for 67,083.40
- Scrolling the ladder scrolls the volume profile in lockstep
- Zooming the ladder zooms the volume profile identically

**Critical — `tickSize` in row iteration:**
The "for each visible price level p" loop must step by
`instrumentSpec.tickSize()` per row, NOT by 1. For BTCUSDT
(`tickSize=1L`) this is the same thing. For CME ES (`tickSize=25L`,
Phase 4) each row represents a 0.25 price increment. See ADR-002
Part 6 for the full analysis.

The `rowY(price, view)` function must be **identical** between the
ladder painter and the volume profile painter. If implemented as
separate methods, they must produce the same output for the same
inputs. The recommended approach is a shared static utility:

```java
// In a shared utility or on ViewState:
static double rowY(long price, long referencePrice, long tickSize,
                   double rowHeightPx, long scrollOffsetPx) {
    long rowIndex = (referencePrice - price) / tickSize;
    return rowIndex * rowHeightPx - scrollOffsetPx;
}
```

This prevents alignment drift between panels.

---

## 6. `ColorScheme` additions

```java
// ── Volume profile ─────────────────────────────────────────────
public final Color volumeBar;         // Bar fill color
public final Color volumeBarText;     // Qty label on/near bars
```

### 6.1 Dark theme values
```java
volumeBar     = Color.web("#2a9d8f"),   // teal — neutral, not buy or sell
volumeBarText = Color.web("#8ecfc5"),   // lighter teal for labels
```

### 6.2 Light theme values
```java
volumeBar     = Color.web("#2a9d8f"),   // same teal
volumeBarText = Color.web("#1a6e64"),   // darker teal for contrast on light bg
```

**Why teal:** The ladder already uses green (bids/buy) and red
(asks/sell). The volume profile is aggressively neutral — it shows
total activity without directional bias. Teal is visually distinct
from both green and red, preventing confusion about which side the
volume represents.

---

## 7. `RenderConfig` additions

```java
// In RenderConfig:
private volatile boolean volumeProfileEnabled = true;

public boolean volumeProfileEnabled()                   { return volumeProfileEnabled; }
public void setVolumeProfileEnabled(boolean enabled)    { this.volumeProfileEnabled = enabled; }
```

### 7.1 Toggle behavior
When `volumeProfileEnabled` is `false`, the painter fills the panel
background and draws the divider but skips all bar rendering. The
`VolumeAccumulator` continues accumulating in the background so that
toggling back on shows the full session's data.

---

## 8. UI changes

### 8.1 Right pane switch — `LadderCanvas` / `MuralisApp`

The `AnimationTimer` frame loop changes which painter runs on the
right-side canvas:

```java
// Phase 2 (before):
bubblePainter.paint(rightGc, snap, viewState, colorScheme);

// Phase 3 (after):
volumeProfilePainter.paint(rightGc, snap, viewState, colorScheme, renderConfig);
```

The right-side `Canvas` object stays the same — same 280px width,
same binding to parent height. Only the painter changes.

`BubblePainter` is NOT deleted. It stays in the codebase, just not
called from the `AnimationTimer`. It will be reactivated in Phase 4
on the heatmap canvas.

### 8.2 `ControlBar` changes

**New controls:**
```java
CheckBox volumeToggle = new CheckBox("Vol");
volumeToggle.setSelected(true);
volumeToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
    renderConfig.setVolumeProfileEnabled(newVal);
});

Button resetVolume = new Button("Reset Vol");
resetVolume.setOnAction(e -> volumeResetCallback.run());
```

**Decay slider behavior:**
The decay slider controlled bubble persistence. With bubbles removed,
it has no visible effect. Two options:
- **Option A (recommended):** Hide or disable the slider. Re-enable
  in Phase 4 when volume dots on the heatmap use a decay window.
- **Option B:** Keep it visible but inactive. The engine still uses
  `bubbleDecayMs` for `TradeBuffer.getActive()`, which populates
  `recentTrades` in the snapshot — but nothing renders them.

### 8.3 Control bar layout (Phase 3)

```
[ ● Connected ] [ ☑ Delta ====○==== 50% ] [ Reset Δ ] [ ☑ Vol ] [ Reset Vol ] [ − ] [ + ] [ ◎ ]
```

The decay slider is removed from the control bar. The delta and volume
controls sit side by side.

### 8.4 Callback wiring

```java
// In Application.main():
MuralisApp.volumeResetCallback = () -> engine.requestVolumeReset();
```

Same `Runnable` callback pattern as `deltaResetCallback`. No engine
import in `ui/`.

---

## 9. What this feature does NOT include (Phase 4)

- Volume dots on the scrolling heatmap
- Buy/sell volume split within profile bars
- Point-of-control (POC) line — the single price with highest volume
- Value area (VA) shading — the range containing 70% of volume
- Time-windowed volume (rolling N minutes instead of session)
- Scrolling liquidity heatmap

These are Phase 4+ features. Phase 3 is deliberately minimal — a
session-cumulative total volume bar per price level.

---

## 10. Implementation sequence

### Step P3.1 — `VolumeAccumulator` + engine integration
- Create `VolumeAccumulator.java` in `com.muralis.engine`
- Modify `OrderBookEngine.applyTrade()` to call
  `volumeAccumulator.accumulate(t.price(), t.qty())`
- Modify `OrderBookEngine.applyConnectionEvent()` to call `clear()`
- Add `requestVolumeReset()` with volatile flag
- Unit test: accumulate 5 trades at 3 prices → verify volumes

### Step P3.2 — `RenderSnapshot` + `buildSnapshot()` changes
- Add `priceVolumeMap` and `maxVolume` to `RenderSnapshot`
- Modify `buildSnapshot()` to construct the map via
  `volumeAccumulator.getSnapshot()`
- Verify existing tests still pass (RenderSnapshot constructor changes)

### Step P3.3 — `ColorScheme` + `RenderConfig` additions
- Add `volumeBar` and `volumeBarText` to both themes
- Add `volumeProfileEnabled` volatile boolean to `RenderConfig`

### Step P3.4 — `VolumeProfilePainter`
- Create `VolumeProfilePainter.java` in `com.muralis.ui`
- Implement paint sequence from Section 5.3
- Linear bar width normalization
- Qty labels on bars wider than `MIN_LABEL_WIDTH`
- Shares `ViewState` with ladder for vertical alignment

### Step P3.5 — Layout switch + UI controls
- Replace `bubblePainter.paint()` with `volumeProfilePainter.paint()`
  in the `AnimationTimer` frame loop
- Add volume toggle and reset button to `ControlBar`
- Remove or disable decay slider
- Wire `volumeResetCallback` in `Application.java`
- `BubblePainter` stays in codebase, just not called

### Step P3.6 — Verification
- Run with live data for 5+ minutes
- Verify teal bars appear at traded price levels
- Verify bar width is proportional to volume
- Verify bars align vertically with ladder price rows
- Verify scroll and zoom affect volume profile identically to ladder
- Verify toggle hides/shows bars
- Verify reset clears all bars
- Verify reconnect clears accumulated volume
- Verify delta tint still works independently
- Verify no BubblePainter code is deleted

---

## 11. Dependency impact

| Package | Changes |
|---|---|
| `model/` | None |
| `provider/` | None |
| `ingestion/` | None |
| `engine/` | New `VolumeAccumulator` class. Modified `OrderBookEngine`. Modified `RenderSnapshot`. Modified `RenderConfig`. |
| `ui/` | New `VolumeProfilePainter` class. Modified `LadderCanvas` (painter switch). Modified `ColorScheme` (2 colors). Modified `ControlBar` (toggle + button, decay slider removed/disabled). |
| `Application` | Wire `volumeResetCallback`. |

No new dependencies. No new threads. No ingestion changes.
All changes respect ARCHITECTURE.md Section 4 dependency rules.

---

## 12. Invariant checklist

- [ ] `VolumeAccumulator` is never accessed outside the engine thread
- [ ] `priceVolumeMap` in `RenderSnapshot` is `Map.copyOf()` — immutable
- [ ] `volumeProfileEnabled` in `RenderConfig` is `volatile`
- [ ] `volumeResetRequested` in `OrderBookEngine` is `volatile`
- [ ] Painter checks `volumeProfileEnabled` FIRST — skips bars when off
- [ ] `VolumeAccumulator` keeps running when toggle is off
- [ ] Volume profile uses same `ViewState` as ladder (shared scroll/zoom)
- [ ] Bars grow LEFT from right edge (anchored at right)
- [ ] Qty labels only on bars wider than `MIN_LABEL_WIDTH`
- [ ] `VolumeAccumulator.clear()` called on CONNECTING and RECONNECTING
- [ ] UI accesses engine reset via `Runnable` callback, not direct import
- [ ] `BubblePainter` is NOT deleted — only disconnected from AnimationTimer
- [ ] `TradeBuffer` continues to accumulate (engine still calls applyTrade)
- [ ] `recentTrades` field stays in `RenderSnapshot` (Phase 4 needs it)
- [ ] No `double` or `float` stores a price or volume — `long` only
- [ ] Bar color is teal (neutral) — NOT green or red

---

*SPEC-phase3-volume-profile.md v1.0 — Phase 3 scope locked.*
