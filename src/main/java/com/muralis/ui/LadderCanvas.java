package com.muralis.ui;

import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.model.InstrumentSpec;
import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.concurrent.atomic.AtomicReference;

public class LadderCanvas extends Region {

    private static final double BUBBLE_PANEL_W   = 280.0;
    private static final double CONNECTING_FONT  = 16.0;

    // ── Dependencies ──────────────────────────────────────────────────────
    private final AtomicReference<RenderSnapshot> snapshotRef;
    @SuppressWarnings("unused")   // held for MuralisApp wiring; decay read via setDecayMs()
    private final RenderConfig                    renderConfig;
    private final HeatmapCanvas                   heatmapCanvas;

    // ── Canvas nodes ──────────────────────────────────────────────────────
    private final Canvas ladderCanvas = new Canvas();
    private final Canvas bubbleCanvas = new Canvas();

    // ── Painters (non-final: replaced when canvas height changes on resize)
    private LadderView           view;
    private LadderPainter        ladderPainter;
    private BubblePainter        bubblePainter;
    private VolumeProfilePainter volumeProfilePainter;

    // ── View state (UI thread only) ───────────────────────────────────────
    private boolean userScrolled = false;
    private double  lastHeight   = -1.0;   // tracks height for rebuild detection

    // ── Theme ─────────────────────────────────────────────────────────────
    private ColorScheme colorScheme = ColorScheme.DARK;

    // ── Aggregation state (UI thread only) ───────────────────────────────
    private int[]   aggregationLevels     = new int[]{1};
    private int     aggregationLevelIndex = 0;
    private boolean initializedLevels     = false;

    // ─────────────────────────────────────────────────────────────────────
    public LadderCanvas(AtomicReference<RenderSnapshot> snapshotRef,
                        RenderConfig renderConfig,
                        HeatmapCanvas heatmapCanvas) {
        this.snapshotRef   = snapshotRef;
        this.renderConfig  = renderConfig;
        this.heatmapCanvas = heatmapCanvas;

        getChildren().addAll(ladderCanvas, bubbleCanvas);
        setFocusTraversable(true);

        // Initial painters — height 800.0 until first layoutChildren() call
        rebuildView(800.0);

        wireInputHandlers();

        // ── AnimationTimer ────────────────────────────────────────────────
        new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                // Step 1 — only cross-thread read; atomic, no locking (Section 4.1)
                RenderSnapshot snap = snapshotRef.get();
                if (snap == null) {
                    renderConnecting();
                    return;
                }

                // Step 1b — sync tick size from instrument spec
                view.tickSize = snap.instrumentSpec().tickSize();

                // Step 1c — initialize aggregation levels on first snapshot
                if (!initializedLevels) {
                    updateAggregationLevels(snap.instrumentSpec());
                    initializedLevels = true;
                }

                // Step 2 — compute ViewState fields (Section 3.3)
                int  ticksPerRow       = aggregationLevels[aggregationLevelIndex];
                long effectiveTickSize = snap.instrumentSpec().tickSize() * ticksPerRow;

                // Step 2 — auto-centre on mid-price unless user has overridden (Section 3.4)
                long scrollOffsetPx = 0L;
                if (!userScrolled
                        && snap.bidPrices().length > 0
                        && snap.askPrices().length > 0) {
                    long midPrice       = (snap.bidPrices()[0] + snap.askPrices()[0]) / 2;
                    long midBucket      = PriceAggregation.bucketPrice(midPrice, effectiveTickSize);
                    long centreRowIndex = (long)(ladderCanvas.getHeight() / 2.0 / view.rowHeightPx());
                    scrollOffsetPx      = (midBucket / effectiveTickSize - centreRowIndex)
                                          * (long) view.rowHeightPx();
                    view.centreOn(midBucket);
                }

                // Build ViewState — painters still use LadderView (no behavior change in P5.2)
                // ViewState will drive painters from P5.4 onward
                new ViewState(
                    view.rowHeightPx(),
                    scrollOffsetPx,
                    userScrolled,
                    ladderCanvas.getWidth(),
                    ladderCanvas.getHeight(),
                    ticksPerRow,
                    effectiveTickSize
                );

                // Step 3a — paint heatmap (shares LadderView for vertical alignment)
                heatmapCanvas.paint(snap, view);

                // Step 3b — paint ladder; gc.save/restore prevents state leakage (Section 4.1)
                GraphicsContext gc = ladderCanvas.getGraphicsContext2D();
                gc.save();
                ladderPainter.paint(snap);
                gc.restore();

                // Step 4 — paint volume profile (replaced BubblePainter in Phase 3)
                GraphicsContext gcb = bubbleCanvas.getGraphicsContext2D();
                gcb.save();
                volumeProfilePainter.paint(snap);
                gcb.restore();
            }
        }.start();
    }

    // ── Region layout ─────────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        double w       = getWidth();
        double h       = getHeight();
        double ladderW = Math.max(0.0, w - BUBBLE_PANEL_W);

        ladderCanvas.setLayoutX(0);
        ladderCanvas.setLayoutY(0);
        ladderCanvas.setWidth(ladderW);
        ladderCanvas.setHeight(h);

        bubbleCanvas.setLayoutX(ladderW);
        bubbleCanvas.setLayoutY(0);
        bubbleCanvas.setWidth(BUBBLE_PANEL_W);
        bubbleCanvas.setHeight(h);

        // Rebuild LadderView whenever canvas height changes so priceToY stays centred
        if (Math.abs(h - lastHeight) > 1.0) {
            rebuildView(h);
            lastHeight = h;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Adjust row height by delta, clamped to [MIN_ROW_PX, MAX_ROW_PX]. */
    public void adjustZoom(double delta) {
        view.adjustZoom(delta);
    }

    /** Reset manual scroll; auto-centring resumes on the next frame. */
    public void resetScroll() {
        userScrolled = false;
        view.resetScroll();
    }

    public ColorScheme colorScheme() {
        return colorScheme;
    }

    /**
     * Called by MuralisApp's decay slider listener; syncs BubblePainter
     * with the same decayMs written to RenderConfig.
     */
    public void setDecayMs(long decayMs) {
        bubblePainter.decayMs = decayMs;
    }

    /** Swap theme; next AnimationTimer frame picks up the new colors. */
    public void setColorScheme(ColorScheme scheme) {
        colorScheme                      = scheme;
        ladderPainter.colorScheme        = scheme;
        bubblePainter.colorScheme        = scheme;
        volumeProfilePainter.colorScheme = scheme;
        heatmapCanvas.setColorScheme(scheme);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Replaces view and painters with fresh instances sized to {@code h}.
     * Preserves centrePrice, rowHeightPx, and scroll offset across rebuilds.
     */
    private void rebuildView(double h) {
        long   savedCentre = (view != null) ? view.centrePrice()       : 0L;
        double savedScroll = (view != null) ? view.scrollOffsetTicks() : 0.0;
        double savedRowH   = (view != null) ? view.rowHeightPx()       : 20.0;

        view = new LadderView(h);
        view.adjustZoom(savedRowH - 20.0);   // restore zoom: delta from default 20px
        view.centreOn(savedCentre);
        if (userScrolled) view.adjustScroll(savedScroll);  // restore from initial 0

        ladderPainter        = new LadderPainter(ladderCanvas, view, colorScheme, renderConfig);
        bubblePainter        = new BubblePainter(bubbleCanvas, view, colorScheme);
        volumeProfilePainter = new VolumeProfilePainter(bubbleCanvas, view, colorScheme, renderConfig);
    }

    /** Rendered when snapshotRef.get() == null (engine not yet ready). */
    private void renderConnecting() {
        double          w  = ladderCanvas.getWidth();
        double          h  = ladderCanvas.getHeight();
        GraphicsContext gc = ladderCanvas.getGraphicsContext2D();
        gc.save();
        gc.setFill(colorScheme.background);
        gc.fillRect(0, 0, w, h);
        gc.setFill(colorScheme.priceText);
        gc.setFont(Font.font(CONNECTING_FONT));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("Connecting\u2026", w / 2, h / 2);
        gc.restore();
    }

    /** Recompute the aggregation level array from the instrument spec. */
    private void updateAggregationLevels(InstrumentSpec spec) {
        aggregationLevels = PriceAggregation.computeAggregationLevels(spec);
    }

    /** Wire all input event handlers (Sections 4.3–4.5). */
    private void wireInputHandlers() {
        // Mouse scroll on the ladder canvas
        ladderCanvas.setOnScroll(event -> {
            if (event.isControlDown()) {
                // Ctrl + scroll → zoom (Section 4.3)
                double delta = event.getDeltaY() > 0 ? 1.0 : -1.0;
                view.adjustZoom(delta * 2.0);
            } else {
                // Scroll without Ctrl → vertical scroll; override auto-centre
                double deltaTicks = event.getDeltaY() * -1.5 / view.rowHeightPx();
                view.adjustScroll(deltaTicks);
                userScrolled = true;
            }
            event.consume();
        });

        // Double-click → resume auto-centre (Section 4.4)
        ladderCanvas.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                userScrolled = false;
                view.resetScroll();
            }
        });

        // Click → request focus so keyboard events are delivered
        ladderCanvas.setOnMousePressed(event -> requestFocus());

        // Keyboard (Section 4.5)
        setOnKeyPressed(event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.PLUS || code == KeyCode.EQUALS) {
                view.adjustZoom(+2.0);
            } else if (code == KeyCode.MINUS) {
                view.adjustZoom(-2.0);
            } else if (code == KeyCode.HOME) {
                userScrolled = false;
                view.resetScroll();
            } else if (code == KeyCode.T) {
                setColorScheme(colorScheme == ColorScheme.DARK
                        ? ColorScheme.LIGHT : ColorScheme.DARK);
            } else {
                return;
            }
            event.consume();
        });
    }

    // ── ViewState record (Section 3.3) ────────────────────────────────────
    // Carries the immutable per-frame view parameters used by painters.
    // ticksPerRow and effectiveTickSize are wired in Phase 5.2;
    // painters adopt ViewState in Phase 5.4 onward.
    private record ViewState(
        double  rowHeightPx,
        long    scrollOffsetPx,
        boolean userScrolled,
        double  canvasWidth,
        double  canvasHeight,
        int     ticksPerRow,
        long    effectiveTickSize
    ) {}
}
