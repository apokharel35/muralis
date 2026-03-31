package com.muralis.ui;

import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.model.InstrumentSpec;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

class LadderPainter {

    private static final double MAX_DELTA_ALPHA = 0.35;

    private final Canvas       canvas;
    private final LadderView   view;
    private final RenderConfig renderConfig;
    ColorScheme colorScheme;   // package-private — LadderCanvas swaps on theme toggle
    int         ticksPerRow = 1; // package-private — set by LadderCanvas before each paint

    // Section 10: NumberFormat instantiated once, reused every frame
    private final NumberFormat priceFormat = NumberFormat.getNumberInstance(Locale.US);

    LadderPainter(Canvas canvas, LadderView view, ColorScheme colorScheme,
                  RenderConfig renderConfig) {
        this.canvas       = canvas;
        this.view         = view;
        this.colorScheme  = colorScheme;
        this.renderConfig = renderConfig;
    }

    void paint(RenderSnapshot snap) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w  = canvas.getWidth();
        double h  = canvas.getHeight();

        InstrumentSpec spec    = snap.instrumentSpec();
        long           centreP = view.centrePrice();
        double         rowH    = view.rowHeightPx();
        long           tickSz  = spec.tickSize();
        long           ets     = tickSz * ticksPerRow;   // effective tick size

        // ── Section 5.1 zone geometry ──────────────────────────────────────
        double bidZoneW       = w * 0.35;
        double priceZoneCentX = w * 0.50;
        double askZoneX       = w * 0.65;
        double askZoneW       = w * 0.35;

        // ── Visible bucket range ───────────────────────────────────────────
        // Derived directly from canvas geometry; bypasses view.priceToY so
        // the formula works for any ticksPerRow (not just native tick = 1).
        double scrollOff    = view.scrollOffsetTicks();
        double halfRows     = h / (2.0 * rowH);
        // topBucket: highest-price bucket on screen (+ets margin for partial rows)
        long   topBucket    = PriceAggregation.bucketPrice(
                centreP + (long)(ets * (halfRows - scrollOff)) + ets, ets);
        // bottomBucket: lowest-price bucket on screen
        long   bottomBucket = PriceAggregation.bucketPrice(
                centreP - (long)(ets * (halfRows + scrollOff)), ets);

        // ── Best bid / ask ─────────────────────────────────────────────────
        long bestBid = snap.bidPrices().length > 0 ? snap.bidPrices()[0] : -1L;
        long bestAsk = snap.askPrices().length > 0 ? snap.askPrices()[0] : -1L;

        // Bucket-aligned best bid/ask for spread and highlight detection
        long bestBidBucket = (bestBid >= 0) ? PriceAggregation.bucketPrice(bestBid, ets) : -1L;
        long bestAskBucket = (bestAsk >= 0) ? PriceAggregation.bucketPrice(bestAsk, ets) : -1L;

        // ── Per-frame max visible qty for bar width normalisation ──────────
        long maxBidQty = 1L;   // never 0 — avoids division by zero
        long maxAskQty = 1L;
        if (ticksPerRow == 1) {
            // Fast path: raw arrays (identical to Phase 4 behaviour)
            for (int i = 0; i < snap.bidPrices().length; i++) {
                long p = snap.bidPrices()[i];
                if (p < bottomBucket) break;
                if (p > topBucket) continue;
                if (snap.bidQtys()[i] > maxBidQty) maxBidQty = snap.bidQtys()[i];
            }
            for (int i = 0; i < snap.askPrices().length; i++) {
                long p = snap.askPrices()[i];
                if (p > topBucket) continue;
                if (p < bottomBucket) break;
                if (snap.askQtys()[i] > maxAskQty) maxAskQty = snap.askQtys()[i];
            }
        } else {
            // Aggregated: find max bucket sum across visible range
            for (long b = topBucket; b >= bottomBucket; b -= ets) {
                long bq = PriceAggregation.bucketQtySum(b, ets, snap.bidPrices(), snap.bidQtys());
                if (bq > maxBidQty) maxBidQty = bq;
            }
            for (long b = topBucket; b >= bottomBucket; b -= ets) {
                long aq = PriceAggregation.bucketQtySum(b, ets, snap.askPrices(), snap.askQtys());
                if (aq > maxAskQty) maxAskQty = aq;
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 1: Fill canvas background
        // ══════════════════════════════════════════════════════════════════
        gc.setFill(colorScheme.background);
        gc.fillRect(0, 0, w, h);

        // ══════════════════════════════════════════════════════════════════
        // Steps 2–4: Row backgrounds, spread fill, best bid/ask highlight
        // ══════════════════════════════════════════════════════════════════
        for (long bucket = topBucket; bucket >= bottomBucket; bucket -= ets) {
            double  y        = bucketToY(bucket, centreP, ets, rowH, h, scrollOff);
            boolean inSpread = bestBidBucket >= 0 && bestAskBucket >= 0
                               && bucket > bestBidBucket && bucket < bestAskBucket;
            boolean isAlt    = ((bucket / ets) % 2L) == 0L;

            if (inSpread) {
                gc.setFill(colorScheme.spreadFill);
            } else if (bucket == bestBidBucket) {
                gc.setFill(colorScheme.bestBidHighlight);
            } else if (bucket == bestAskBucket) {
                gc.setFill(colorScheme.bestAskHighlight);
            } else if (isAlt) {
                gc.setFill(colorScheme.rowAlternate);
            } else {
                gc.setFill(colorScheme.background);
            }
            gc.fillRect(0, y, w, rowH);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 2b: Delta tint overlay
        //          Skips spread and best bid/ask rows.
        // ══════════════════════════════════════════════════════════════════
        if (renderConfig.deltaTintEnabled() && snap.maxAbsDelta() != 0L) {
            for (long bucket = topBucket; bucket >= bottomBucket; bucket -= ets) {
                boolean inSpread = bestBidBucket >= 0 && bestAskBucket >= 0
                                   && bucket > bestBidBucket && bucket < bestAskBucket;
                if (inSpread || bucket == bestBidBucket || bucket == bestAskBucket) continue;

                long delta;
                if (ticksPerRow == 1) {
                    // Fast path: direct map lookup
                    delta = snap.priceDeltaMap().getOrDefault(bucket, 0L);
                } else {
                    delta = PriceAggregation.bucketDeltaSum(bucket, ets,
                                snap.priceDeltaMap(), tickSz);
                }
                if (delta == 0L) continue;

                double logDelta   = Math.log1p(Math.abs(delta));
                double logMax     = Math.log1p(snap.maxAbsDelta());
                double normalised = (logMax > 0) ? logDelta / logMax : 0.0;
                double intensity  = normalised * renderConfig.deltaTintIntensity();
                double alpha      = intensity * MAX_DELTA_ALPHA;
                if (alpha < 0.02) continue;

                Color tint = delta > 0
                        ? colorScheme.deltaBuyTint.deriveColor(0, 1, 1, alpha)
                        : colorScheme.deltaSellTint.deriveColor(0, 1, 1, alpha);

                gc.setFill(tint);
                gc.fillRect(0, bucketToY(bucket, centreP, ets, rowH, h, scrollOff), w, rowH);
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 5: Grid lines
        // ══════════════════════════════════════════════════════════════════
        gc.setStroke(colorScheme.gridLine);
        gc.setLineWidth(1.0);
        for (long bucket = topBucket; bucket >= bottomBucket; bucket -= ets) {
            double y = bucketToY(bucket, centreP, ets, rowH, h, scrollOff);
            gc.strokeLine(0, y, w, y);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 6: Bid bars — bar grows LEFT from the price zone
        // ══════════════════════════════════════════════════════════════════
        gc.setFill(colorScheme.bidBar);
        if (ticksPerRow == 1) {
            // Fast path: raw bid array
            for (int i = 0; i < snap.bidPrices().length; i++) {
                long p = snap.bidPrices()[i];
                if (p < bottomBucket) break;
                if (p > topBucket) continue;
                double barWidth = (double) snap.bidQtys()[i] / (double) maxBidQty * bidZoneW;
                double barX     = bidZoneW - barWidth;
                double barY     = bucketToY(p, centreP, ets, rowH, h, scrollOff);
                gc.fillRect(barX, barY + 1, barWidth, rowH - 2);
            }
        } else {
            // Aggregated: bucket sum
            for (long b = topBucket; b >= bottomBucket; b -= ets) {
                long bidQty = PriceAggregation.bucketQtySum(b, ets,
                                  snap.bidPrices(), snap.bidQtys());
                if (bidQty == 0L) continue;
                double barWidth = (double) bidQty / (double) maxBidQty * bidZoneW;
                double barX     = bidZoneW - barWidth;
                double barY     = bucketToY(b, centreP, ets, rowH, h, scrollOff);
                gc.fillRect(barX, barY + 1, barWidth, rowH - 2);
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 7: Ask bars — bar grows RIGHT from the price zone
        // ══════════════════════════════════════════════════════════════════
        gc.setFill(colorScheme.askBar);
        if (ticksPerRow == 1) {
            // Fast path: raw ask array
            for (int i = 0; i < snap.askPrices().length; i++) {
                long p = snap.askPrices()[i];
                if (p > topBucket) continue;
                if (p < bottomBucket) break;
                double barWidth = (double) snap.askQtys()[i] / (double) maxAskQty * askZoneW;
                double barY     = bucketToY(p, centreP, ets, rowH, h, scrollOff);
                gc.fillRect(askZoneX, barY + 1, barWidth, rowH - 2);
            }
        } else {
            // Aggregated: bucket sum
            for (long b = topBucket; b >= bottomBucket; b -= ets) {
                long askQty = PriceAggregation.bucketQtySum(b, ets,
                                  snap.askPrices(), snap.askQtys());
                if (askQty == 0L) continue;
                double barWidth = (double) askQty / (double) maxAskQty * askZoneW;
                double barY     = bucketToY(b, centreP, ets, rowH, h, scrollOff);
                gc.fillRect(askZoneX, barY + 1, barWidth, rowH - 2);
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 8: Price text
        // ══════════════════════════════════════════════════════════════════
        double priceFontSz = Math.clamp(rowH * 0.55, 9.0, 14.0);
        gc.setFont(Font.font(priceFontSz));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (long bucket = topBucket; bucket >= bottomBucket; bucket -= ets) {
            double  textY    = bucketToY(bucket, centreP, ets, rowH, h, scrollOff) + rowH / 2.0;
            boolean inSpread = bestBidBucket >= 0 && bestAskBucket >= 0
                               && bucket > bestBidBucket && bucket < bestAskBucket;

            if (bucket == bestBidBucket) {
                gc.setFill(colorScheme.bestBidText);
            } else if (bucket == bestAskBucket) {
                gc.setFill(colorScheme.bestAskText);
            } else if (inSpread) {
                gc.setFill(colorScheme.spreadPriceText);
            } else {
                gc.setFill(colorScheme.priceText);
            }
            gc.fillText(formatBucketPrice(bucket, spec), priceZoneCentX, textY);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 9: Qty text — skipped when rowH < 14px
        // ══════════════════════════════════════════════════════════════════
        if (rowH >= 14.0) {
            double qtyFontSz = Math.clamp(rowH * 0.45, 8.0, 11.0);
            gc.setFont(Font.font(qtyFontSz));
            gc.setFill(colorScheme.qtyText);

            if (ticksPerRow == 1) {
                // Fast path: raw arrays
                gc.setTextAlign(TextAlignment.RIGHT);
                for (int i = 0; i < snap.bidPrices().length; i++) {
                    long p = snap.bidPrices()[i];
                    if (p < bottomBucket) break;
                    if (p > topBucket) continue;
                    double rowCentreY = bucketToY(p, centreP, ets, rowH, h, scrollOff) + rowH / 2.0;
                    gc.fillText(formatQty(snap.bidQtys()[i], spec), bidZoneW - 4, rowCentreY);
                }
                gc.setTextAlign(TextAlignment.LEFT);
                for (int i = 0; i < snap.askPrices().length; i++) {
                    long p = snap.askPrices()[i];
                    if (p > topBucket) continue;
                    if (p < bottomBucket) break;
                    double rowCentreY = bucketToY(p, centreP, ets, rowH, h, scrollOff) + rowH / 2.0;
                    gc.fillText(formatQty(snap.askQtys()[i], spec), askZoneX + 4, rowCentreY);
                }
            } else {
                // Aggregated: bucket sum
                gc.setTextAlign(TextAlignment.RIGHT);
                for (long b = topBucket; b >= bottomBucket; b -= ets) {
                    long bidQty = PriceAggregation.bucketQtySum(b, ets,
                                      snap.bidPrices(), snap.bidQtys());
                    if (bidQty == 0L) continue;
                    double rowCentreY = bucketToY(b, centreP, ets, rowH, h, scrollOff) + rowH / 2.0;
                    gc.fillText(formatQty(bidQty, spec), bidZoneW - 4, rowCentreY);
                }
                gc.setTextAlign(TextAlignment.LEFT);
                for (long b = topBucket; b >= bottomBucket; b -= ets) {
                    long askQty = PriceAggregation.bucketQtySum(b, ets,
                                      snap.askPrices(), snap.askQtys());
                    if (askQty == 0L) continue;
                    double rowCentreY = bucketToY(b, centreP, ets, rowH, h, scrollOff) + rowH / 2.0;
                    gc.fillText(formatQty(askQty, spec), askZoneX + 4, rowCentreY);
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 10: Panel divider line
        // ══════════════════════════════════════════════════════════════════
        gc.setStroke(colorScheme.panelDivider);
        gc.setLineWidth(1.0);
        gc.strokeLine(w - 1, 0, w - 1, h);
    }

    // Y coordinate for a bucket price using effective tick size.
    // Formula: Y = H/2 + [(centreP - bucket)/ets - scrollOff] * rowH
    // For ticksPerRow == 1 this is identical to the old view.priceToY() result.
    private static double bucketToY(long bucket, long centreP, long ets,
                                    double rowH, double h, double scrollOff) {
        double rowOffset = (double)(centreP - bucket) / ets - scrollOff;
        return h / 2.0 + rowOffset * rowH;
    }

    // Price label: for ticksPerRow == 1 use NumberFormat (commas, fixed decimals);
    // for ticksPerRow > 1 use stripTrailingZeros so "67083.00" becomes "67083" (Section 5.2).
    private String formatBucketPrice(long bucket, InstrumentSpec spec) {
        if (ticksPerRow == 1) {
            return formatPrice(bucket, spec);
        }
        return BigDecimal.valueOf(bucket, spec.priceScale())
                         .stripTrailingZeros().toPlainString();
    }

    // Section 5.6 — uses BigDecimal.valueOf(unscaled, scale) as specified.
    // NumberFormat instance reused (priceFormat field, not instantiated here).
    private String formatPrice(long price, InstrumentSpec spec) {
        BigDecimal bd = BigDecimal.valueOf(price, spec.priceScale());
        return priceFormat.format(bd);
    }

    // Section 5.7 — strips trailing zeros, returns plain string (no scientific notation).
    private String formatQty(long qty, InstrumentSpec spec) {
        BigDecimal bd = BigDecimal.valueOf(qty, spec.qtyScale());
        return bd.stripTrailingZeros().toPlainString();
    }
}
