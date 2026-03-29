# SPEC-rendering.md — Muralis

> This spec defines the `ui/` package in full. It covers the JavaFX
> application entry point, canvas layout, AnimationTimer frame loop,
> ladder painting, bubble painting, color scheme, zoom/scroll behaviour,
> connection status indicator, and the decay window slider.
>
> **Claude Code instruction:** All classes generated from this spec live
> in `com.muralis.ui`. They may import from `com.muralis.model` and
> `com.muralis.engine.RenderSnapshot`, `com.muralis.engine.TradeBlip`,
> and `com.muralis.engine.RenderConfig` only. No imports from
> `com.muralis.ingestion` or `com.muralis.provider` are permitted.
> See `ARCHITECTURE.md` Section 4.

---

## 1. Scope

This spec covers exactly the following classes:

| Class | Responsibility |
|---|---|
| `MuralisApp` | JavaFX `Application` subclass. Window, scene, layout. |
| `LadderCanvas` | Canvas container. Owns `AnimationTimer`. Handles input events. |
| `LadderPainter` | All ladder draw calls (price rows, bid/ask bars, spread). |
| `BubblePainter` | All trade bubble draw calls in the side panel. |
| `ColorScheme` | All color constants for dark and light themes. Single source of truth. |

No other classes belong in `com.muralis.ui`. All rendering logic is
expressed as methods on these five classes.

---

## 2. Window and layout structure

### 2.1 JavaFX scene graph

```
Stage (MuralisApp)
└── Scene
    └── BorderPane (root)
        ├── TOP    → StatusBar     (HBox — connection indicator + symbol label)
        ├── CENTER → LadderCanvas  (Canvas — ladder + controls overlay)
        └── RIGHT  → BubblePanel   (Canvas — trade bubble side panel)
        └── BOTTOM → ControlBar    (HBox — decay slider + zoom reset button)
```

### 2.2 Initial window dimensions

| Element | Width | Height |
|---|---|---|
| Full window | 1,100px | 800px |
| `LadderCanvas` | 700px | fills CENTER |
| `BubblePanel` | 280px | fills RIGHT |
| `StatusBar` | full width | 28px |
| `ControlBar` | full width | 36px |

Minimum window size: 900px × 600px. Window is resizable. Canvas
components resize with the window via `BorderPane` layout constraints.
Canvas `width` and `height` properties must be bound to the parent
container dimensions using:
```java
canvas.widthProperty().bind(parentPane.widthProperty());
canvas.heightProperty().bind(parentPane.heightProperty());
```

### 2.3 Theme switching

The `ColorScheme` class exposes two static instances: `DARK` and `LIGHT`.
The active scheme is stored in a single field on `LadderCanvas`:
```java
private ColorScheme colorScheme = ColorScheme.DARK;  // default
```

A toggle button in the `StatusBar` switches between schemes. On toggle:
1. Set `ladderCanvas.colorScheme` to the new scheme
2. Set `bubblePanel.colorScheme` to the new scheme
3. The next `AnimationTimer` frame picks up the new colors automatically
   — no explicit repaint call needed

---

## 3. `ColorScheme` — class specification

All colors used anywhere in the rendering pipeline are defined here and
only here. No hex color literal appears in `LadderPainter` or
`BubblePainter`. They reference `colorScheme.fieldName` exclusively.

```java
package com.muralis.ui;

public final class ColorScheme {

    // ── Canvas backgrounds ─────────────────────────────────────────
    public final Color background;        // Main canvas fill
    public final Color panelBackground;   // Bubble panel fill

    // ── Ladder rows ────────────────────────────────────────────────
    public final Color rowAlternate;      // Subtle alternating row tint
    public final Color spreadFill;        // Fill for rows inside the spread
    public final Color bestBidHighlight;  // Full-row highlight for best bid row
    public final Color bestAskHighlight;  // Full-row highlight for best ask row

    // ── Bid / Ask bars ─────────────────────────────────────────────
    public final Color bidBar;            // Bid quantity bar fill
    public final Color askBar;            // Ask quantity bar fill

    // ── Price text ─────────────────────────────────────────────────
    public final Color priceText;         // Default price label colour
    public final Color bestBidText;       // Price text on best bid row
    public final Color bestAskText;       // Price text on best ask row
    public final Color spreadPriceText;   // Price text on spread rows

    // ── Quantity text ──────────────────────────────────────────────
    public final Color qtyText;           // Bid/ask quantity labels

    // ── Trade bubbles ──────────────────────────────────────────────
    public final Color buyBubbleFill;     // Aggressive buy bubble fill
    public final Color buyBubbleStroke;   // Aggressive buy bubble border
    public final Color sellBubbleFill;    // Aggressive sell bubble fill
    public final Color sellBubbleStroke;  // Aggressive sell bubble border
    public final Color bubbleQtyText;     // Qty label inside bubble

    // ── Status indicator ───────────────────────────────────────────
    public final Color statusConnected;    // Green dot
    public final Color statusConnecting;   // Amber dot
    public final Color statusReconnecting; // Amber dot (same as connecting)
    public final Color statusDisconnected; // Red dot

    // ── Grid lines ─────────────────────────────────────────────────
    public final Color gridLine;          // Subtle horizontal separator
    public final Color panelDivider;      // Vertical line between ladder and panel
}
```

### 3.1 Dark theme values

```java
public static final ColorScheme DARK = new ColorScheme(
    background         = Color.web("#0d0d0f"),
    panelBackground    = Color.web("#111114"),
    rowAlternate       = Color.web("#131316"),
    spreadFill         = Color.web("#1a1a2e"),
    bestBidHighlight   = Color.web("#0d2b1a"),   // deep green tint
    bestAskHighlight   = Color.web("#2b0d0d"),   // deep red tint
    bidBar             = Color.web("#1a6b3a"),   // muted green
    askBar             = Color.web("#6b1a1a"),   // muted red
    priceText          = Color.web("#c8c8d0"),
    bestBidText        = Color.web("#4dff91"),   // bright green
    bestAskText        = Color.web("#ff4d4d"),   // bright red
    spreadPriceText    = Color.web("#8888aa"),
    qtyText            = Color.web("#909098"),
    buyBubbleFill      = Color.web("#1db954"),   // vivid green
    buyBubbleStroke    = Color.web("#4dff91"),
    sellBubbleFill     = Color.web("#e63946"),   // vivid red
    sellBubbleStroke   = Color.web("#ff6b6b"),
    bubbleQtyText      = Color.web("#ffffff"),
    statusConnected    = Color.web("#1db954"),
    statusConnecting   = Color.web("#f4a261"),
    statusReconnecting = Color.web("#f4a261"),
    statusDisconnected = Color.web("#e63946"),
    gridLine           = Color.web("#1e1e24"),
    panelDivider       = Color.web("#2a2a35")
);
```

### 3.2 Light theme values

```java
public static final ColorScheme LIGHT = new ColorScheme(
    background         = Color.web("#f5f5f7"),
    panelBackground    = Color.web("#ebebee"),
    rowAlternate       = Color.web("#efeff2"),
    spreadFill         = Color.web("#e8e8f5"),
    bestBidHighlight   = Color.web("#d4f0e0"),
    bestAskHighlight   = Color.web("#f0d4d4"),
    bidBar             = Color.web("#2d9e5f"),
    askBar             = Color.web("#9e2d2d"),
    priceText          = Color.web("#1a1a2e"),
    bestBidText        = Color.web("#0a7a3a"),
    bestAskText        = Color.web("#9e1a1a"),
    spreadPriceText    = Color.web("#6666aa"),
    qtyText            = Color.web("#555566"),
    buyBubbleFill      = Color.web("#1db954"),
    buyBubbleStroke    = Color.web("#0a7a3a"),
    sellBubbleFill     = Color.web("#e63946"),
    sellBubbleStroke   = Color.web("#9e1a1a"),
    bubbleQtyText      = Color.web("#ffffff"),
    statusConnected    = Color.web("#1db954"),
    statusConnecting   = Color.web("#e07820"),
    statusReconnecting = Color.web("#e07820"),
    statusDisconnected = Color.web("#e63946"),
    gridLine           = Color.web("#dcdce0"),
    panelDivider       = Color.web("#c0c0cc")
);
```

---

## 4. `LadderCanvas` — class specification

`LadderCanvas` is a JavaFX `Pane` containing a `Canvas`. It owns the
`AnimationTimer`, all input event handlers, and the view state (scroll
offset and zoom level).

```java
package com.muralis.ui;

public class LadderCanvas extends Pane {

    // Dependencies (injected via constructor)
    private final AtomicReference<RenderSnapshot> snapshotRef;
    private final RenderConfig                    renderConfig;

    // Painters
    private final LadderPainter ladderPainter;
    private final Canvas        canvas;

    // View state (UI thread only)
    private double rowHeightPx    = 20.0;   // Current row height in pixels
    private long   scrollOffsetPx = 0L;     // Signed pixel offset from auto-centre
    private boolean userScrolled  = false;  // True if user has overridden auto-centre

    // Theme
    ColorScheme colorScheme = ColorScheme.DARK;
}
```

### 4.1 `AnimationTimer` frame loop

```java
new AnimationTimer() {
    @Override
    public void handle(long nowNanos) {
        RenderSnapshot snap = snapshotRef.get();
        if (snap == null) {
            renderConnecting();
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.save();
        ladderPainter.paint(gc, snap, viewState());
        gc.restore();
    }
}.start();
```

**Rules:**
- `snapshotRef.get()` is the only cross-thread read. It is atomic.
  No locking. No `Platform.runLater`.
- `gc.save()` / `gc.restore()` wraps every frame to prevent state leakage
  between frames (transform, clip, fill, stroke state).
- If `snap == null`: clear canvas, draw centred "Connecting…" text in
  `colorScheme.priceText`, return. Do not attempt to paint a ladder.
- Every frame repaints the entire canvas. No dirty-region tracking in
  Phase 1. At 60 FPS with ~100 visible rows, full repaint is fast enough.

### 4.2 View state

```java
private record ViewState(
    double rowHeightPx,
    long   scrollOffsetPx,
    boolean userScrolled,
    double canvasWidth,
    double canvasHeight
) {}
```

`ViewState` is a snapshot of rendering geometry captured at the start of
each frame. It is passed to `LadderPainter.paint()` so the painter is
stateless and does not touch `LadderCanvas` fields directly.

### 4.3 Mouse wheel zoom

```java
canvas.setOnScroll(event -> {
    if (event.isControlDown()) {
        // Ctrl + scroll → zoom (change row height)
        double delta = event.getDeltaY() > 0 ? 1.0 : -1.0;
        rowHeightPx = Math.clamp(rowHeightPx + delta * 2.0, MIN_ROW_PX, MAX_ROW_PX);
        event.consume();
    } else {
        // Scroll without Ctrl → vertical scroll (override auto-centre)
        scrollOffsetPx += (long)(event.getDeltaY() * -1.5);
        userScrolled = true;
        event.consume();
    }
});
```

| Constant | Value | Meaning |
|---|---|---|
| `MIN_ROW_PX` | `10.0` | Minimum row height (very compressed) |
| `MAX_ROW_PX` | `60.0` | Maximum row height (very zoomed in) |
| Default `rowHeightPx` | `20.0` | ~40 rows visible at 800px height |

### 4.4 Auto-centring

When `userScrolled == false`, the ladder auto-centres on the mid-price
every frame:

```
midPrice   = (snap.bestBid() + snap.bestAsk()) / 2
centreRow  = (canvasHeight / 2) / rowHeightPx
targetOffset = midPrice row index − centreRow
scrollOffsetPx = targetOffset * rowHeightPx
```

When `userScrolled == true`, auto-centring is suppressed. A double-click
on the canvas resets `userScrolled = false` and resumes auto-centring.

A "Return to centre" button in the `ControlBar` also resets `userScrolled`.

### 4.5 Keyboard input

| Key | Action |
|---|---|
| `+` / `=` | Increase row height by 2px (zoom in) |
| `-` | Decrease row height by 2px (zoom out) |
| `Home` | Reset scroll offset, resume auto-centre |
| `T` | Toggle theme (dark ↔ light) |

---

## 5. `LadderPainter` — class specification

`LadderPainter` is a stateless utility class. All rendering state is
passed in via `RenderSnapshot` and `ViewState`. It holds no mutable
fields. Every method is called exclusively from the JavaFX
Application Thread inside the `AnimationTimer`.

```java
package com.muralis.ui;

public class LadderPainter {

    public void paint(
        GraphicsContext gc,
        RenderSnapshot  snap,
        ViewState       view,
        ColorScheme     scheme
    )
}
```

### 5.1 Layout geometry

The ladder occupies the full `LadderCanvas` width. Three logical columns:

```
|←── bidBarZone ──→|←─── priceZone ───→|←── askBarZone ──→|
0                 30%       50%        70%              100%
```

| Zone | Left edge | Width |
|---|---|---|
| Bid bar zone | `0` | `canvasWidth × 0.35` |
| Price zone | `canvasWidth × 0.35` | `canvasWidth × 0.30` |
| Ask bar zone | `canvasWidth × 0.65` | `canvasWidth × 0.35` |

Price text is centred in the price zone.
Bid quantity text is right-aligned at the left edge of the price zone.
Ask quantity text is left-aligned at the right edge of the price zone.

### 5.2 Visible price levels

```
visibleRows    = ceil(canvasHeight / rowHeightPx) + 2   // +2 for partial top/bottom rows
topPriceIndex  = centreIndex + (scrollOffsetPx / rowHeightPx)
bottomPriceIndex = topPriceIndex + visibleRows
```

Only price levels within `[topPriceIndex, bottomPriceIndex]` are painted.
Levels outside this range are skipped entirely — this is the primary
performance guard for a full-book snapshot.

### 5.3 Paint order (per frame)

Executed in this exact order to ensure correct z-layering:

```
1. Fill canvas background     → gc.fillRect(full canvas, scheme.background)
2. Paint row backgrounds      → one rect per visible row
3. Paint spread highlight     → filled rects for spread rows
4. Paint best bid/ask rows    → filled rects with scheme.bestBid/AskHighlight
5. Paint grid lines           → horizontal lines between rows
6. Paint bid bars             → filled rects, width ∝ qty, grows LEFT from price zone
7. Paint ask bars             → filled rects, width ∝ qty, grows RIGHT from price zone
8. Paint price text           → centred in price zone per row
9. Paint qty text             → bid qty right of bar, ask qty left of bar
10. Paint panel divider line  → right edge of canvas
```

Nothing is painted outside this order. No step may be skipped even if
it produces zero visible output (e.g. step 3 when there is no spread).

### 5.4 Row background painting

```
for each visible price level p:
    y = rowY(p)                     // top pixel of this row
    isAlt = (priceIndex % 2 == 0)

    if p is inside spread:
        fill = scheme.spreadFill
    else if p == bestBid:
        fill = scheme.bestBidHighlight
    else if p == bestAsk:
        fill = scheme.bestAskHighlight
    else if isAlt:
        fill = scheme.rowAlternate
    else:
        fill = scheme.background

    gc.setFill(fill)
    gc.fillRect(0, y, canvasWidth, rowHeightPx)
```

**Spread rows** are all price levels strictly between `bestBid` and
`bestAsk` (exclusive). On a 1-tick spread these rows are empty. On a
wide spread (e.g. thin book) multiple rows appear in `scheme.spreadFill`.

### 5.5 Bid and ask bar painting

Bar width is proportional to the quantity at that level relative to the
**maximum quantity visible** on that side in the current frame.

```
maxBidQty = max(bidQtys[i]) for all visible bid levels
maxAskQty = max(askQtys[i]) for all visible ask levels

for each visible bid level i:
    barWidth = (bidQtys[i] / maxBidQty) × bidBarZoneWidth
    barX     = bidBarZoneWidth - barWidth   // bar grows LEFT from price zone
    barY     = rowY(bidPrices[i])
    gc.setFill(scheme.bidBar)
    gc.fillRect(barX, barY + 1, barWidth, rowHeightPx - 2)

for each visible ask level i:
    barWidth = (askQtys[i] / maxAskQty) × askBarZoneWidth
    barX     = canvasWidth × 0.65           // bar grows RIGHT from price zone
    barY     = rowY(askPrices[i])
    gc.setFill(scheme.askBar)
    gc.fillRect(barX, barY + 1, barWidth, rowHeightPx - 2)
```

**Max normalisation is per-frame and per-side.** It recomputes every
frame from visible levels only. A level with very large qty that scrolls
off screen does not compress all visible bars.

**Bar height inset:** `rowHeightPx - 2` leaves a 1px gap top and bottom,
preventing bars from touching and giving a clean row separation.

### 5.6 Price text painting

```
font size = clamp(rowHeightPx × 0.55, 9.0, 14.0)   // scales with zoom

for each visible price level p:
    displayPrice = formatPrice(p, instrumentSpec)    // e.g. "97,432.51"
    textX        = priceZoneCentreX
    textY        = rowY(p) + rowHeightPx / 2

    if p == bestBid:   gc.setFill(scheme.bestBidText)
    elif p == bestAsk: gc.setFill(scheme.bestAskText)
    elif inSpread(p):  gc.setFill(scheme.spreadPriceText)
    else:              gc.setFill(scheme.priceText)

    gc.setTextAlign(CENTER)
    gc.setTextBaseline(VPos.CENTER)
    gc.fillText(displayPrice, textX, textY)
```

**Price formatting:**
```java
private String formatPrice(long price, InstrumentSpec spec) {
    BigDecimal bd = BigDecimal.valueOf(price, spec.priceScale());
    return NumberFormat.getNumberInstance(Locale.US).format(bd);
    // Output: "97,432.51" for BTCUSDT
}
```

Price formatting uses `BigDecimal.valueOf(unscaledVal, scale)` — the
correct zero-allocation-at-boundary conversion. `NumberFormat` is
instantiated once and reused.

### 5.7 Qty text painting

Quantity text is painted only when `rowHeightPx >= 14.0`. Below this
threshold rows are too compressed for readable text — skip qty labels.

```
font size = clamp(rowHeightPx × 0.45, 8.0, 11.0)
gc.setFill(scheme.qtyText)

for each visible bid level:
    displayQty = formatQty(qty, instrumentSpec)
    textX = bidBarZoneWidth - 4      // right-aligned, 4px from price zone
    gc.setTextAlign(RIGHT)
    gc.fillText(displayQty, textX, rowCentreY)

for each visible ask level:
    displayQty = formatQty(qty, instrumentSpec)
    textX = canvasWidth × 0.65 + 4  // left-aligned, 4px from price zone
    gc.setTextAlign(LEFT)
    gc.fillText(displayQty, textX, rowCentreY)
```

**Qty formatting:**
```java
private String formatQty(long qty, InstrumentSpec spec) {
    // For crypto (qtyScale=8): show 4 decimal places max
    // 41800L with scale 8 → "0.0004"  (not "0.00041800" — too wide)
    BigDecimal bd = BigDecimal.valueOf(qty, spec.qtyScale());
    return bd.stripTrailingZeros().toPlainString();
}
```

---

## 6. `BubblePainter` — class specification

`BubblePainter` renders trade bubbles in the `BubblePanel` — a
separate `Canvas` to the right of the ladder. Bubbles appear at the
vertical position corresponding to their traded price level, sized
by quantity, and fade out as they age toward the decay window.

```java
package com.muralis.ui;

public class BubblePainter {

    public void paint(
        GraphicsContext  gc,
        RenderSnapshot   snap,
        ViewState        view,      // used for price→pixel mapping
        ColorScheme      scheme
    )
}
```

### 6.1 Bubble panel layout

The bubble panel is a fixed-width canvas (`280px` default) with the same
vertical price-to-pixel mapping as the ladder. A bubble at price `p`
appears at the same vertical `y` as price `p` on the ladder, keeping
the two panels visually aligned.

```
panelWidth = 280px
Bubbles are placed horizontally by age: newest at LEFT edge, oldest at RIGHT edge.
As time passes, bubbles drift RIGHT and fade.
New bubbles always enter from the LEFT side of the panel.
```

### 6.2 Bubble sizing — logarithmic scale

```java
private double bubbleDiameter(long qty, InstrumentSpec spec) {
    double qtyDouble = (double) qty / Math.pow(10, spec.qtyScale());
    double logQty    = Math.log10(Math.max(qtyDouble, 0.001) + 1.0);

    double MIN_DIAMETER = 6.0;
    double MAX_DIAMETER = Math.min(view.rowHeightPx() * 2.5, 60.0);

    // logQty at typical BTC trade sizes:
    // 0.001 BTC → log10(1.001) ≈ 0.0004  → ~6px
    // 0.1   BTC → log10(1.1)   ≈ 0.041   → ~12px
    // 1.0   BTC → log10(2.0)   ≈ 0.301   → ~28px
    // 10.0  BTC → log10(11.0)  ≈ 1.041   → ~54px (capped)

    double normalised = logQty / Math.log10(11.0);   // normalise to [0,1] at 10 BTC
    return MIN_DIAMETER + normalised * (MAX_DIAMETER - MIN_DIAMETER);
}
```

`MAX_DIAMETER` is capped at `rowHeightPx × 2.5` so bubbles never grow
larger than 2.5 rows — they remain readable at all zoom levels.
Hard cap at `60px` prevents enormous bubbles for outlier block trades.

### 6.3 Bubble alpha — decay over time

```java
private double bubbleAlpha(TradeBlip blip, long decayMs) {
    long ageMs  = System.currentTimeMillis() - blip.receivedTs();
    double life = 1.0 - ((double) ageMs / decayMs);
    return Math.clamp(life, 0.0, 1.0);
}
```

Alpha `1.0` = fully opaque (brand new trade).
Alpha `0.0` = fully transparent (expired — not painted).
Bubbles with `alpha < 0.02` are skipped entirely (no draw call).

**Why `receivedTs` not `exchangeTs`:** Decay is a display concern — "how
long has this bubble been on my screen?" — not a data concern. Using
`exchangeTs` would cause clock skew: if the exchange clock is ahead of
the local clock, new trades would appear partially faded. `receivedTs`
is the local time the trade arrived, making decay visually consistent.

### 6.4 Bubble horizontal position — time-based drift

```java
private double bubbleX(TradeBlip blip, long decayMs, double panelWidth) {
    long   ageMs      = System.currentTimeMillis() - blip.receivedTs();
    double lifeRatio  = (double) ageMs / decayMs;   // 0.0 = new, 1.0 = expired
    return lifeRatio * (panelWidth - MAX_DIAMETER);  // drifts right over lifetime
}
```

New bubbles appear at `x ≈ 0` (left edge). At decay window expiry they
reach `x ≈ panelWidth`. The drift is linear over the decay window.

### 6.5 Bubble vertical position — price alignment

```java
private double bubbleY(TradeBlip blip, ViewState view) {
    // Same price→pixel mapping as the ladder painter
    long   priceIndex = priceToRowIndex(blip.price(), view);
    double y          = priceIndex * view.rowHeightPx() - view.scrollOffsetPx();
    return y + view.rowHeightPx() / 2.0;   // centre vertically on the price row
}
```

The bubble panel and ladder panel share the same `ViewState`, so a
bubble always aligns horizontally with its corresponding price row.

### 6.6 Bubble paint sequence (per frame)

```
1. Fill panel background → gc.fillRect(full panel, scheme.panelBackground)
2. Draw panel divider    → 1px vertical line at left edge (scheme.panelDivider)
3. For each blip in snap.recentTrades(), newest first:
   a. Compute alpha → skip if alpha < 0.02
   b. Compute diameter, x, y
   c. Compute fill and stroke color with alpha applied:
          Color fill   = blip.aggressorSide() == BUY
                         ? scheme.buyBubbleFill.deriveColor(0,1,1,alpha)
                         : scheme.sellBubbleFill.deriveColor(0,1,1,alpha)
          Color stroke = blip.aggressorSide() == BUY
                         ? scheme.buyBubbleStroke.deriveColor(0,1,1,alpha)
                         : scheme.sellBubbleStroke.deriveColor(0,1,1,alpha)
   d. gc.setFill(fill)
      gc.fillOval(x - r, y - r, diameter, diameter)
   e. gc.setStroke(stroke)
      gc.setLineWidth(1.0)
      gc.strokeOval(x - r, y - r, diameter, diameter)
   f. If diameter >= 18px: paint qty label inside bubble
          gc.setFill(scheme.bubbleQtyText.deriveColor(0,1,1,alpha))
          gc.setTextAlign(CENTER)
          gc.setTextBaseline(VPos.CENTER)
          gc.fillText(formatQtyShort(blip.qty(), spec), x, y)
4. Drawing is naturally bounded by the panel Canvas dimensions —
      no gc.clip() call is needed or permitted (see Section 10).
```

**Qty label inside bubble** (`formatQtyShort`):
```
0.00041800 BTC → "0.0004"
1.25000000 BTC → "1.25"
10.5000000 BTC → "10.5"
```
Show at most 4 significant figures. Use `BigDecimal.stripTrailingZeros()`
then truncate to 4 chars if longer. No unit suffix inside the bubble —
too cramped.

---

## 7. Status bar specification

The `StatusBar` is an `HBox` in the `BorderPane` TOP slot. Height: 28px.
Background: `colorScheme.background` with a 1px bottom border in
`colorScheme.gridLine`.

### 7.1 Elements (left to right)

```
[●] [BTCUSDT]  [CONNECTED]               [⊙ Dark]
 │       │          │                        │
 │       │          └── ConnectionState text  └── Theme toggle button
 │       └── Symbol label
 └── Status dot (8px circle, color = status color from ColorScheme)
```

### 7.2 Status dot color

| `ConnectionState` | Dot color |
|---|---|
| `CONNECTED` | `scheme.statusConnected` (green) |
| `CONNECTING` | `scheme.statusConnecting` (amber) — pulses opacity 0.4↔1.0 at 1Hz |
| `RECONNECTING` | `scheme.statusReconnecting` (amber) — same pulse as CONNECTING |
| `DISCONNECTED` | `scheme.statusDisconnected` (red) — no pulse |

The amber pulse is implemented with a `Timeline`:
```java
Timeline pulse = new Timeline(
    new KeyFrame(Duration.ZERO,        new KeyValue(dot.opacityProperty(), 1.0)),
    new KeyFrame(Duration.seconds(0.5),new KeyValue(dot.opacityProperty(), 0.4)),
    new KeyFrame(Duration.seconds(1.0),new KeyValue(dot.opacityProperty(), 1.0))
);
pulse.setCycleCount(Animation.INDEFINITE);
```
Start pulse on `CONNECTING`/`RECONNECTING`. Stop and reset opacity to
`1.0` on `CONNECTED` or `DISCONNECTED`.

### 7.3 Updating status from engine

The status bar reads `connectionState` from `RenderSnapshot` on every
`AnimationTimer` frame. When the state changes, update the dot color
and label text. Do not subscribe to engine events directly — the
snapshot is the single source of truth for the UI.

---

## 8. Control bar specification

The `ControlBar` is an `HBox` in the `BorderPane` BOTTOM slot.
Height: 36px. Padding: 6px horizontal, 4px vertical.

### 8.1 Elements

```
[Decay: 5s] [←────●──────────────────] [Centre] [+ Zoom -]
      │              │                     │          │
      │              └── Slider (1s–30s)   │          └── Zoom buttons
      │                                    └── Return to centre button
      └── Label (updates with slider value)
```

### 8.2 Decay slider

```java
Slider decaySlider = new Slider(1.0, 30.0, 5.0);
decaySlider.setMajorTickUnit(5.0);
decaySlider.setMinorTickCount(4);   // 500ms steps between major ticks
decaySlider.setSnapToTicks(true);
decaySlider.setShowTickMarks(false);
decaySlider.setPrefWidth(220.0);

decaySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
    long decayMs = newVal.longValue() * 1_000L;
    renderConfig.setBubbleDecayMs(decayMs);
    decayLabel.setText("Decay: " + newVal.intValue() + "s");
});
```

`renderConfig` is the shared `RenderConfig` instance from `Application`.
The slider writes to `renderConfig.setBubbleDecayMs()` which is
`volatile` — engine thread reads it on the next snapshot build.

### 8.3 Centre button

```java
Button centreButton = new Button("Centre");
centreButton.setOnAction(e -> {
    ladderCanvas.resetScroll();   // sets userScrolled = false, scrollOffsetPx = 0
});
```

### 8.4 Zoom buttons

```java
Button zoomIn  = new Button("+");
Button zoomOut = new Button("−");
zoomIn.setOnAction(e  -> ladderCanvas.adjustZoom(+2.0));
zoomOut.setOnAction(e -> ladderCanvas.adjustZoom(-2.0));
```

`adjustZoom(double delta)` clamps to `[MIN_ROW_PX, MAX_ROW_PX]` as
defined in Section 4.3.

---

## 9. `MuralisApp` — class specification

```java
package com.muralis.ui;

public class MuralisApp extends Application {

    // Injected before launch via static fields (JavaFX Application
    // does not support constructor injection — see Section 9.1)
    public static AtomicReference<RenderSnapshot> snapshotRef;
    public static RenderConfig                    renderConfig;
    public static InstrumentSpec                  instrumentSpec;
    public static Runnable                        shutdownCallback;
    // shutdownCallback calls adapter.disconnect(). Set by Application.main()
    // so MuralisApp never imports from ingestion/ or provider/.

    @Override
    public void start(Stage stage) {
        // Build scene graph per Section 2.1
        // Wire all input handlers per Sections 4.3–4.5
        // Start AnimationTimer
        // Configure stage title, min size, close handler
    }

    @Override
    public void stop() {
        // Called by JavaFX on window close
        if (shutdownCallback != null) {
            shutdownCallback.run();  // triggers adapter.disconnect()
        }
        // AnimationTimer stops automatically when stage closes
    }
}
```

### 9.1 JavaFX launch pattern

JavaFX's `Application.launch()` instantiates `MuralisApp` via reflection
with no constructor arguments. Dependencies must be passed via static
fields set before `launch()` is called in `Application.main()`:

```java
// In Application.main():
MuralisApp.snapshotRef      = snapshotRef;
MuralisApp.renderConfig     = renderConfig;
MuralisApp.instrumentSpec   = instrumentSpec;
MuralisApp.shutdownCallback = () -> adapter.disconnect();
Application.launch(MuralisApp.class, args);
```

This is the standard JavaFX pattern for injecting dependencies.
It is not a DI anti-pattern in this context — it is an unavoidable
constraint of the JavaFX launch lifecycle. The `shutdownCallback`
`Runnable` avoids importing `MarketDataProvider` or `BinanceAdapter`
into `com.muralis.ui` — preserving the dependency rule that `ui/` never
imports from `provider/` or `ingestion/`.

### 9.2 Stage configuration

```
title:    "Muralis — BTCUSDT"
minWidth: 900
minHeight: 600
onCloseRequest: → shutdownCallback.run(), then Platform.exit()
```

---

## 10. Rendering performance rules

These rules must not be violated. They are the difference between 60 FPS
and a janky, unusable tool.

| Rule | Rationale |
|---|---|
| Never call `gc.clip()` | Allocates a canvas-sized texture per call |
| Never use paths for rectangles | Use `fillRect()` exclusively |
| Batch by fill color | Minimise `setFill()` state switches |
| Skip invisible bubbles early | `alpha < 0.02` → no draw call |
| Skip off-screen rows early | Price index outside visible range → skip |
| Font set once per frame | `gc.setFont()` outside the row loop |
| `NumberFormat` instantiated once | Reused across all price/qty format calls |
| No object allocation inside paint loop | No `new Color()`, `new String()` per row |
| `gc.save()` / `gc.restore()` per frame | Prevents state leakage between frames |

---

## 11. Invariant checklist (Claude Code enforcement)

When generating any class in `com.muralis.ui`, verify:

- [ ] No import from `com.muralis.ingestion` exists anywhere
- [ ] No import from `com.muralis.provider` exists anywhere
- [ ] `snapshotRef.get()` is the only read of engine state
- [ ] No `Platform.runLater()` call exists — UI thread only
- [ ] `gc.save()` / `gc.restore()` wraps every `AnimationTimer` frame
- [ ] `gc.clip()` is never called
- [ ] `fillRect()` is used for all rectangle drawing (never paths)
- [ ] All color literals live in `ColorScheme` — none in painter classes
- [ ] Bubble alpha < 0.02 results in a skipped draw call, not a transparent draw
- [ ] Bubble decay uses `blip.receivedTs()` not `blip.exchangeTs()` — avoids clock skew
- [ ] `formatPrice()` and `formatQty()` use `BigDecimal.valueOf(unscaled, scale)`
- [ ] `NumberFormat` instance is created once, not per frame
- [ ] Decay slider writes to `renderConfig` only — not to any engine field directly
- [ ] `MuralisApp` static fields are set before `Application.launch()` is called
- [ ] `MuralisApp.shutdownCallback` is a `Runnable` set by `Application.main()`
- [ ] Stage `onCloseRequest` calls `shutdownCallback.run()` before `Platform.exit()`

---

*Last updated: SPEC-rendering.md v1.1 — Bubble decay uses receivedTs (not exchangeTs) to avoid clock skew. gc.clip() contradiction resolved. shutdownCallback pattern added to MuralisApp. Invariant checklist updated.*
*Next file: SPEC-provider-spi.md*
