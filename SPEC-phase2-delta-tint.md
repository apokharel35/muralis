# SPEC-phase2-delta-tint.md — Muralis Phase 2

> This spec defines the Phase 2 feature: per-price-level aggressor
> delta tinting on the DOM ladder. Each price row's background is
> tinted green or red based on the net difference between aggressive
> buy volume and aggressive sell volume that has traded at that price
> since session start (or last manual reset).
>
> **Prerequisites:** Phase 1 complete through Step 9. All unit tests
> passing. Live data pipeline verified.

---

## 1. Feature summary

### What the trader sees
Every price row on the DOM ladder has a background tint:
- **Green tint** → more aggressive buying than selling at this price
- **Red tint** → more aggressive selling than buying at this price
- **No tint** → no trades at this price, or buy/sell volume is equal
- **Intensity** → proportional to how extreme the delta is, scaled by
  a user-controlled slider

This tells the trader at a glance: "Where did aggressive participants
react, and in which direction?" Levels with strong green tint are
prices where buyers dominated. Levels with strong red tint are prices
where sellers dominated. This is the core order flow signal that
Bookmap's volume dots and delta coloring provide.

### What does NOT change
- The existing bid/ask resting liquidity bars remain unchanged
- The existing trade bubbles in the side panel remain unchanged
- The price text, spread highlight, and best bid/ask rows remain
- The engine thread model, queue, and snapshot pattern remain
- No new threads, no new dependencies, no ingestion changes

---

## 2. Data model — `DeltaAccumulator`

A new class in `com.muralis.engine` that accumulates per-price
aggressor volume from every `NormalizedTrade`.

```java
package com.muralis.engine;

public class DeltaAccumulator {

    // key = price (fixed-point long), value = [buyVolume, sellVolume]
    // both in fixed-point using instrumentSpec.qtyScale
    private final HashMap<Long, long[]> priceDeltas;

    public void accumulate(long price, long qty, AggressorSide side) {
        long[] volumes = priceDeltas.computeIfAbsent(price, k -> new long[2]);
        if (side == AggressorSide.BUY) {
            volumes[0] += qty;   // index 0 = buy volume
        } else {
            volumes[1] += qty;   // index 1 = sell volume
        }
    }

    public long getDelta(long price) {
        long[] volumes = priceDeltas.get(price);
        if (volumes == null) return 0L;
        return volumes[0] - volumes[1];  // positive = net buying
    }

    public long getMaxAbsDelta() {
        // Returns the largest |delta| across all price levels.
        // Used for normalizing tint intensity.
    }

    public void clear() {
        priceDeltas.clear();
    }
}
```

### 2.1 Ownership and thread safety
`DeltaAccumulator` is owned exclusively by the engine thread. No
synchronization needed. Same ownership model as `OrderBook` and
`TradeBuffer`.

### 2.2 Memory behavior
Each entry is a `Long` key + `long[2]` value ≈ 40 bytes. At 2,000
traded price levels per session (typical for BTC over several hours):
```
2,000 levels × 40 bytes ≈ 80 KB
```
Negligible. No eviction needed for Phase 2. Phase 3 may add time-based
windowing if memory grows over very long sessions.

---

## 3. Engine changes — `OrderBookEngine`

### 3.1 New field
```java
private final DeltaAccumulator deltaAccumulator = new DeltaAccumulator();
```

### 3.2 `applyTrade` modification
After the existing duplicate check and `TradeBlip` creation, add:

```java
deltaAccumulator.accumulate(t.price(), t.qty(), t.aggressorSide());
```

This is a single `HashMap.computeIfAbsent` + addition. Sub-microsecond.
No impact on the < 1ms processing constraint.

### 3.3 `applyConnectionEvent` modification
On `CONNECTING` and `RECONNECTING`, clear the accumulator along with
the order book and trade buffer:

```java
deltaAccumulator.clear();
```

This ensures accumulated deltas don't survive a reconnect where the
order book is rebuilt from scratch.

### 3.4 `buildSnapshot` modification
Add delta data to `RenderSnapshot`. See Section 4.

### 3.5 Reset support
A new method on the engine, callable from the UI via a thread-safe
mechanism:

```java
// In OrderBookEngine:
private volatile boolean deltaResetRequested = false;

public void requestDeltaReset() {
    deltaResetRequested = true;
}

// In runLoop, before buildSnapshot:
if (deltaResetRequested) {
    deltaAccumulator.clear();
    deltaResetRequested = false;
}
```

The UI thread sets the flag; the engine thread clears the accumulator
on its next iteration. No locking needed — single volatile boolean.

---

## 4. `RenderSnapshot` changes

Add two new fields to the `RenderSnapshot` record:

```java
public record RenderSnapshot(
    // ... existing fields ...
    long[]  deltaPrices,       // Price levels that have traded (fixed-point)
    long[]  deltaValues,       // Net delta at each price: buy_vol - sell_vol
    long    maxAbsDelta        // Largest |delta| across all levels (for normalization)
) {}
```

### 4.1 Construction in `buildSnapshot()`

```java
// After existing bid/ask array construction:
Map<Long, long[]> rawDeltas = deltaAccumulator.getSnapshot();
int deltaSize = rawDeltas.size();
long[] deltaPrices = new long[deltaSize];
long[] deltaValues = new long[deltaSize];
long maxAbsDelta = 0L;
int i = 0;
for (Map.Entry<Long, long[]> entry : rawDeltas.entrySet()) {
    deltaPrices[i] = entry.getKey();
    long delta = entry.getValue()[0] - entry.getValue()[1];
    deltaValues[i] = delta;
    maxAbsDelta = Math.max(maxAbsDelta, Math.abs(delta));
    i++;
}
```

**Note:** `deltaPrices` is NOT sorted — the painter looks up delta by
price, not by position. A `HashMap` lookup during painting would
require building a map from the arrays on every frame. Instead, the
painter builds a transient `HashMap<Long, Long>` from the arrays once
per frame (or the snapshot could carry a `Map` directly — implementation
choice, not architectural).

### 4.2 Alternative: carry a Map in the snapshot

```java
public record RenderSnapshot(
    // ... existing fields ...
    Map<Long, Long> priceDeltaMap,  // price → net delta
    long            maxAbsDelta
) {}
```

This avoids the painter building a map from arrays every frame. The
map is created in `buildSnapshot()` via `Map.copyOf()` — immutable.
**This is the recommended approach.**

---

## 5. Rendering changes — `LadderPainter`

### 5.1 New paint step

Insert a new step in the paint order from SPEC-rendering.md Section 5.3,
**after step 2 (row backgrounds) and before step 3 (spread highlight)**:

```
Existing paint order:
1. Fill canvas background
2. Paint row backgrounds
2b. ★ NEW: Paint delta tint overlay ★
3. Paint spread highlight
4. Paint best bid/ask rows
5. Paint grid lines
6-10. (unchanged)
```

### 5.2 Delta tint painting

```
if (!renderConfig.deltaTintEnabled()) return;  // toggle off → skip entirely

for each visible price level p:
    delta = snap.priceDeltaMap().getOrDefault(p, 0L)
    if delta == 0L: skip (no tint)

    maxDelta = snap.maxAbsDelta()
    if maxDelta == 0L: skip (no trades anywhere)

    // Normalize: how extreme is this level's delta relative to max?
    double normalised = (double) Math.abs(delta) / maxDelta

    // Apply user intensity slider (0.0 = off, 1.0 = full)
    double intensity = normalised * renderConfig.deltaTintIntensity()

    // Cap at maximum opacity to keep price text readable
    double alpha = Math.min(intensity * MAX_DELTA_ALPHA, MAX_DELTA_ALPHA)

    if alpha < 0.02: skip (invisible)

    Color tint = delta > 0
        ? scheme.deltaBuyTint.deriveColor(0, 1, 1, alpha)
        : scheme.deltaSellTint.deriveColor(0, 1, 1, alpha)

    gc.setFill(tint)
    gc.fillRect(0, rowY(p), canvasWidth, rowHeightPx)
```

### 5.3 Constants

| Constant | Value | Meaning |
|---|---|---|
| `MAX_DELTA_ALPHA` | `0.35` | Maximum tint opacity — keeps text readable |

### 5.4 Why paint BEFORE spread highlight

The delta tint is a background layer. The spread highlight, best
bid/ask highlights, and grid lines paint on top of it. This means:
- Spread rows still show their distinctive blue/purple tint
- Best bid/ask rows still show their green/red highlight
- Delta tint is visible on normal rows (not spread, not best)
- Price text is always readable on top of the tint

---

## 6. `ColorScheme` additions

```java
// ── Delta tint ──────────────────────────────────────────────────
public final Color deltaBuyTint;      // Green tint for net buying
public final Color deltaSellTint;     // Red tint for net selling
```

### 6.1 Dark theme values
```java
deltaBuyTint   = Color.web("#1db954"),   // same green as buyBubbleFill
deltaSellTint  = Color.web("#e63946"),   // same red as sellBubbleFill
```

### 6.2 Light theme values
```java
deltaBuyTint   = Color.web("#1db954"),
deltaSellTint  = Color.web("#e63946"),
```

The alpha is applied dynamically in the painter — the base color is
fully opaque in `ColorScheme`, and `deriveColor()` applies the
per-row alpha.

---

## 7. `RenderConfig` additions

```java
// In RenderConfig:
private volatile boolean deltaTintEnabled = true;     // toggle on/off
private volatile double  deltaTintIntensity = 0.5;    // 0.0 = min, 1.0 = max

public boolean deltaTintEnabled()                      { return deltaTintEnabled; }
public void setDeltaTintEnabled(boolean enabled)       { this.deltaTintEnabled = enabled; }
public double deltaTintIntensity()                     { return deltaTintIntensity; }
public void setDeltaTintIntensity(double intensity)    { this.deltaTintIntensity = intensity; }
```

### 7.1 Toggle behavior
When `deltaTintEnabled` is `false`, the painter skips paint step 2b
entirely — zero overhead. The `DeltaAccumulator` continues accumulating
in the background so that toggling back on shows the full session's
data, not a fresh start.

### 7.2 Slider constraints (enforced by UI)

| Constraint | Value |
|---|---|
| Minimum | `0.1` (very faint — 0.0 is handled by the toggle) |
| Maximum | `1.0` (maximum intensity) |
| Default | `0.5` |
| Step | `0.1` increments |
| Disabled state | Slider grayed out when toggle is off |

---

## 8. UI changes — `ControlBar`

### 8.1 Delta tint toggle

```java
CheckBox deltaTintToggle = new CheckBox("Delta");
deltaTintToggle.setSelected(true);
deltaTintToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
    renderConfig.setDeltaTintEnabled(newVal);
    deltaTintSlider.setDisable(!newVal);
});
```

When unchecked, the slider grays out and the painter skips the tint
entirely. The accumulator keeps running in the background.

### 8.2 Delta intensity slider

```java
Slider deltaTintSlider = new Slider(0.1, 1.0, 0.5);
deltaTintSlider.setMajorTickUnit(0.2);
deltaTintSlider.setSnapToTicks(false);
deltaTintSlider.setPrefWidth(120.0);

Label deltaTintLabel = new Label("50%");

deltaTintSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
    renderConfig.setDeltaTintIntensity(newVal.doubleValue());
    deltaTintLabel.setText((int)(newVal.doubleValue() * 100) + "%");
});
```

### 8.3 Delta reset button

```java
Button resetDelta = new Button("Reset Δ");
resetDelta.setOnAction(e -> engine.requestDeltaReset());
```

**Note:** `engine` reference — `MuralisApp` needs access to the
`OrderBookEngine` instance for the reset call. Pass it via a static
field (same pattern as `shutdownCallback`) or pass a `Runnable`:

```java
// In Application.main():
MuralisApp.deltaResetCallback = () -> engine.requestDeltaReset();
```

This avoids importing `engine/` in `ui/` — the callback is a plain
`Runnable`.

### 8.4 Control bar layout

```
[ ● Connected ] [ Decay ====○==== ] [ ☑ Delta  ====○==== 50% ] [ Reset Δ ] [ − ] [ + ] [ ◎ ]
```

---

## 9. What this feature does NOT include (deferred to later phases)

### Phase 3 — Session volume profile
- Right pane repurposed from bubble drift to volume profile bars
- Horizontal bars showing total traded volume at each price level
- Accumulated since session start (or last reset)
- Bubbles removed from right pane

### Phase 4 — Scrolling heatmap with volume dots
- New LEFT pane added to the layout
- Time scrolls left-to-right like a chart
- Resting liquidity heatmap: color intensity = order book depth at
  each price over time (blue=thin, warm=thick)
- Volume dots appear on the scrolling chart at the price and time
  where trades executed (green=buy, red=sell, sized by qty)
- BBO price line traces through time
- This is the "Bookmap view" — the signature visualization

### Layout evolution
```
Phase 1 (current): [  ladder  ] [ bubbles    ]
Phase 2:           [  ladder  ] [ bubbles    ]  (+ toggleable delta tint)
Phase 3:           [  ladder  ] [ vol profile]  (bubbles removed)
Phase 4:           [ heatmap  ] [  ladder    ] [ vol profile ]
```

---

## 10. Implementation sequence

### Step P2.1 — `DeltaAccumulator` + engine integration
- Create `DeltaAccumulator.java` in `com.muralis.engine`
- Modify `OrderBookEngine.applyTrade()` to call `accumulate()`
- Modify `OrderBookEngine.applyConnectionEvent()` to call `clear()`
- Add `requestDeltaReset()` with volatile flag
- Unit test: accumulate 3 buys and 2 sells at same price → delta = net

### Step P2.2 — `RenderSnapshot` + `buildSnapshot()` changes
- Add `priceDeltaMap` and `maxAbsDelta` to `RenderSnapshot`
- Modify `buildSnapshot()` to construct the map
- Verify existing tests still pass

### Step P2.3 — `ColorScheme` + `RenderConfig` additions
- Add `deltaBuyTint` and `deltaSellTint` to both themes
- Add `deltaTintIntensity` volatile field to `RenderConfig`
- No functional change yet — just data plumbing

### Step P2.4 — `LadderPainter` delta tint rendering
- Add paint step 2b between row backgrounds and spread highlight
- Use `priceDeltaMap` from snapshot, normalize against `maxAbsDelta`
- Apply `deltaTintIntensity` from `RenderConfig`
- Cap at `MAX_DELTA_ALPHA = 0.35`
- Skip rows with alpha < 0.02

### Step P2.5 — UI controls
- Add delta intensity slider to `ControlBar`
- Add "Reset Δ" button to `ControlBar`
- Wire slider to `renderConfig.setDeltaTintIntensity()`
- Wire button to `deltaResetCallback` Runnable

### Step P2.6 — Verification
- Run with live data for 5+ minutes
- Verify green tint appears at price levels with net buying
- Verify red tint appears at price levels with net selling
- Verify slider changes tint intensity in real time
- Verify reset button clears all tinting
- Verify tint does not obscure price text
- Verify tint does not appear on spread rows or best bid/ask rows
- Verify reconnect clears accumulated deltas

---

## 11. Dependency impact

| Package | Changes |
|---|---|
| `model/` | None |
| `provider/` | None |
| `ingestion/` | None |
| `engine/` | New `DeltaAccumulator` class. Modified `OrderBookEngine`. Modified `RenderSnapshot`. Modified `RenderConfig`. |
| `ui/` | Modified `LadderPainter` (new paint step). Modified `ColorScheme` (2 colors). Modified `ControlBar` (slider + button). New `Runnable` static field on `MuralisApp`. |
| `Application` | Wire `deltaResetCallback`. |

No new dependencies. No new threads. No ingestion changes.
All changes respect ARCHITECTURE.md Section 4 dependency rules.

---

## 12. Invariant checklist

- [ ] `DeltaAccumulator` is never accessed outside the engine thread
- [ ] `priceDeltaMap` in `RenderSnapshot` is `Map.copyOf()` — immutable
- [ ] `deltaTintEnabled` in `RenderConfig` is `volatile`
- [ ] `deltaTintIntensity` in `RenderConfig` is `volatile`
- [ ] `deltaResetRequested` in `OrderBookEngine` is `volatile`
- [ ] Painter checks `deltaTintEnabled` FIRST — skips entirely when off
- [ ] `DeltaAccumulator` keeps running when tint is toggled off
- [ ] Delta tint is painted BEFORE spread highlight and best bid/ask
- [ ] Alpha < 0.02 → skip draw call (no invisible fills)
- [ ] Alpha capped at `MAX_DELTA_ALPHA` to keep text readable
- [ ] `DeltaAccumulator.clear()` called on CONNECTING and RECONNECTING
- [ ] UI accesses engine reset via `Runnable` callback, not direct import
- [ ] Slider is disabled (grayed out) when toggle is off
- [ ] No `double` or `float` stores a price or volume — `long` only
- [ ] Delta calculation is `buyVol - sellVol` (not the reverse)

---

*SPEC-phase2-delta-tint.md v1.1 — Added toggle on/off for delta tint. Slider disabled when toggle off. Accumulator keeps running in background. Full roadmap (Phase 3 volume profile, Phase 4 heatmap) documented in Section 9.*
