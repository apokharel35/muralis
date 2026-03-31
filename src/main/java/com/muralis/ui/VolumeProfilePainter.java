package com.muralis.ui;

import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.model.InstrumentSpec;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.math.BigDecimal;

class VolumeProfilePainter {

    private static final double PANEL_WIDTH     = 280.0;
    private static final double LABEL_MARGIN    = 8.0;
    private static final double MIN_LABEL_WIDTH = 40.0;

    private final Canvas       canvas;
    private final LadderView   view;
    private final RenderConfig renderConfig;
    ColorScheme colorScheme;   // package-private — LadderCanvas swaps on theme toggle

    VolumeProfilePainter(Canvas canvas, LadderView view, ColorScheme colorScheme,
                         RenderConfig renderConfig) {
        this.canvas       = canvas;
        this.view         = view;
        this.colorScheme  = colorScheme;
        this.renderConfig = renderConfig;
    }

    void paint(RenderSnapshot snap) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double h = canvas.getHeight();

        // ── Step 1: check toggle ───────────────────────────────────────────
        if (!renderConfig.volumeProfileEnabled()) {
            gc.setFill(colorScheme.panelBackground);
            gc.fillRect(0, 0, PANEL_WIDTH, h);
            gc.setStroke(colorScheme.panelDivider);
            gc.setLineWidth(1.0);
            gc.strokeLine(0, 0, 0, h);
            return;
        }

        // ── Step 2: fill panel background ─────────────────────────────────
        gc.setFill(colorScheme.panelBackground);
        gc.fillRect(0, 0, PANEL_WIDTH, h);

        // ── Step 3: draw left-edge divider ────────────────────────────────
        gc.setStroke(colorScheme.panelDivider);
        gc.setLineWidth(1.0);
        gc.strokeLine(0, 0, 0, h);

        // ── Step 4 & 5: bars and labels ───────────────────────────────────
        InstrumentSpec spec    = snap.instrumentSpec();
        long           tickSz  = spec.tickSize();
        long           centreP = view.centrePrice();
        double         rowH    = view.rowHeightPx();

        long topPrice    = view.yToPrice(0, centreP);
        long bottomPrice = view.yToPrice(h, centreP);
        if (topPrice < bottomPrice) {
            long tmp = topPrice;
            topPrice = bottomPrice;
            bottomPrice = tmp;
        }

        double labelFontSz = Math.clamp(rowH * 0.45, 8.0, 11.0);
        Font   labelFont   = Font.font(labelFontSz);

        if (view.ticksPerRow == 1) {
            // ── Fast path: no aggregation ─────────────────────────────────
            long maxVol = snap.maxVolume();
            for (long p = topPrice; p >= bottomPrice; p -= tickSz) {
                long volume = snap.priceVolumeMap().getOrDefault(p, 0L);
                if (volume == 0L || maxVol == 0L) continue;

                double barWidth = (double) volume / maxVol * (PANEL_WIDTH - LABEL_MARGIN);
                double barX     = PANEL_WIDTH - LABEL_MARGIN - barWidth;
                double barY     = view.priceToY(p, centreP) + 1;
                double barH     = rowH - 2;
                if (barH <= 0) continue;

                gc.setFill(colorScheme.volumeBar);
                gc.fillRect(barX, barY, barWidth, barH);

                if (barWidth >= MIN_LABEL_WIDTH) {
                    String label = BigDecimal.valueOf(volume, spec.qtyScale())
                                            .stripTrailingZeros()
                                            .toPlainString();
                    gc.setFill(colorScheme.volumeBarText);
                    gc.setFont(labelFont);
                    gc.setTextAlign(TextAlignment.RIGHT);
                    gc.setTextBaseline(VPos.CENTER);
                    gc.fillText(label, barX - 4, barY + barH / 2.0);
                }
            }
        } else {
            // ── Aggregated path: sum volumes per bucket ───────────────────
            long ets       = tickSz * view.ticksPerRow;
            long topBucket = PriceAggregation.bucketPrice(topPrice, ets);

            // Pre-scan to compute maxVol over visible buckets
            long maxBucketVol = 0L;
            for (long bucket = topBucket; bucket >= bottomPrice; bucket -= ets) {
                long vol = PriceAggregation.bucketVolumeSum(
                        bucket, ets, snap.priceVolumeMap(), tickSz);
                if (vol > maxBucketVol) maxBucketVol = vol;
            }

            for (long bucket = topBucket; bucket >= bottomPrice; bucket -= ets) {
                long vol = PriceAggregation.bucketVolumeSum(
                        bucket, ets, snap.priceVolumeMap(), tickSz);
                if (vol == 0L || maxBucketVol == 0L) continue;

                double barWidth = (double) vol / maxBucketVol * (PANEL_WIDTH - LABEL_MARGIN);
                double barX     = PANEL_WIDTH - LABEL_MARGIN - barWidth;
                double barY     = view.priceToY(bucket, centreP) + 1;
                double barH     = rowH - 2;
                if (barH <= 0) continue;

                gc.setFill(colorScheme.volumeBar);
                gc.fillRect(barX, barY, barWidth, barH);

                if (barWidth >= MIN_LABEL_WIDTH) {
                    String label = BigDecimal.valueOf(vol, spec.qtyScale())
                                            .stripTrailingZeros()
                                            .toPlainString();
                    gc.setFill(colorScheme.volumeBarText);
                    gc.setFont(labelFont);
                    gc.setTextAlign(TextAlignment.RIGHT);
                    gc.setTextBaseline(VPos.CENTER);
                    gc.fillText(label, barX - 4, barY + barH / 2.0);
                }
            }
        }
    }
}
