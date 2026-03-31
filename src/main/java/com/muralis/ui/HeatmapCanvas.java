package com.muralis.ui;

import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;

import java.util.concurrent.atomic.AtomicReference;

public class HeatmapCanvas extends Region {

    // ── Dependencies ──────────────────────────────────────────────────────
    private final AtomicReference<RenderSnapshot> snapshotRef;
    private final RenderConfig                    renderConfig;

    // ── Canvas node ───────────────────────────────────────────────────────
    private final Canvas canvas = new Canvas();

    // ── Painter (rebuilt when view changes) ───────────────────────────────
    private HeatmapPainter heatmapPainter;

    // ── Theme ─────────────────────────────────────────────────────────────
    ColorScheme colorScheme = ColorScheme.DARK;

    // ── Shared view (set externally each frame) ───────────────────────────
    private LadderView view;

    // ─────────────────────────────────────────────────────────────────────
    public HeatmapCanvas(AtomicReference<RenderSnapshot> snapshotRef,
                         RenderConfig renderConfig) {
        this.snapshotRef  = snapshotRef;
        this.renderConfig = renderConfig;

        getChildren().add(canvas);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Called by LadderCanvas's AnimationTimer each frame.
     * Updates the shared LadderView reference, then paints.
     */
    public void paint(RenderSnapshot snap, LadderView view) {
        this.view = view;

        if (heatmapPainter == null || heatmapPainter.view != view) {
            heatmapPainter = new HeatmapPainter(canvas, view, colorScheme, renderConfig);
        }
        heatmapPainter.ticksPerRow = view.ticksPerRow;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.save();
        heatmapPainter.paint(snap);
        gc.restore();
    }

    public void setColorScheme(ColorScheme scheme) {
        this.colorScheme = scheme;
        if (heatmapPainter != null) {
            heatmapPainter.colorScheme = scheme;
        }
    }

    // ── Region layout ─────────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }
}
