package com.muralis.ui;

import com.muralis.engine.RenderSnapshot;
import com.muralis.model.InstrumentSpec;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

class LadderPainter {

    private final Canvas     canvas;
    private final LadderView view;
    ColorScheme colorScheme;   // package-private — LadderCanvas swaps on theme toggle

    // Section 10: NumberFormat instantiated once, reused every frame
    private final NumberFormat priceFormat = NumberFormat.getNumberInstance(Locale.US);

    LadderPainter(Canvas canvas, LadderView view, ColorScheme colorScheme) {
        this.canvas      = canvas;
        this.view        = view;
        this.colorScheme = colorScheme;
    }

    void paint(RenderSnapshot snap) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w  = canvas.getWidth();
        double h  = canvas.getHeight();

        InstrumentSpec spec    = snap.instrumentSpec();
        long           centreP = view.centrePrice();
        double         rowH    = view.rowHeightPx();
        long           tickSz  = spec.tickSize();

        // ── Section 5.1 zone geometry ──────────────────────────────────────
        double bidZoneW       = w * 0.35;
        double priceZoneCentX = w * 0.50;
        double askZoneX       = w * 0.65;
        double askZoneW       = w * 0.35;

        // ── Visible price range (Section 5.2) ─────────────────────────────
        long topPrice    = view.yToPrice(0, centreP);
        long bottomPrice = view.yToPrice(h, centreP);
        // Guard: topPrice must be >= bottomPrice (prices decrease going down)
        if (topPrice < bottomPrice) {
            long tmp = topPrice;
            topPrice = bottomPrice;
            bottomPrice = tmp;
        }

        // ── Best bid / ask ─────────────────────────────────────────────────
        long bestBid = snap.bidPrices().length > 0 ? snap.bidPrices()[0] : -1L;
        long bestAsk = snap.askPrices().length > 0 ? snap.askPrices()[0] : -1L;

        // ── Per-frame max visible qty for bar width normalisation (Section 5.5) ──
        long maxBidQty = 1L;   // never 0 — avoids division by zero
        for (int i = 0; i < snap.bidPrices().length; i++) {
            long p = snap.bidPrices()[i];
            if (p < bottomPrice) break;   // descending — below visible range
            if (p > topPrice) continue;   // above visible range
            if (snap.bidQtys()[i] > maxBidQty) maxBidQty = snap.bidQtys()[i];
        }
        long maxAskQty = 1L;
        for (int i = 0; i < snap.askPrices().length; i++) {
            long p = snap.askPrices()[i];
            if (p > topPrice) continue;   // ascending — above visible range
            if (p < bottomPrice) break;   // below visible range
            if (snap.askQtys()[i] > maxAskQty) maxAskQty = snap.askQtys()[i];
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 1: Fill canvas background (Section 5.3, step 1)
        // ══════════════════════════════════════════════════════════════════
        gc.setFill(colorScheme.background);
        gc.fillRect(0, 0, w, h);

        // ══════════════════════════════════════════════════════════════════
        // Steps 2–4: Row backgrounds, spread fill, best bid/ask highlight
        //            (Section 5.3 steps 2–4; Section 5.4)
        // ══════════════════════════════════════════════════════════════════
        for (long price = topPrice; price >= bottomPrice; price -= tickSz) {
            double y      = view.priceToY(price, centreP);
            boolean isAlt = ((price / tickSz) % 2L) == 0L;
            boolean inSpread = bestBid >= 0 && bestAsk >= 0
                               && price > bestBid && price < bestAsk;

            if (inSpread) {
                gc.setFill(colorScheme.spreadFill);
            } else if (price == bestBid) {
                gc.setFill(colorScheme.bestBidHighlight);
            } else if (price == bestAsk) {
                gc.setFill(colorScheme.bestAskHighlight);
            } else if (isAlt) {
                gc.setFill(colorScheme.rowAlternate);
            } else {
                gc.setFill(colorScheme.background);
            }
            gc.fillRect(0, y, w, rowH);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 5: Grid lines (Section 5.3, step 5)
        // ══════════════════════════════════════════════════════════════════
        gc.setStroke(colorScheme.gridLine);
        gc.setLineWidth(1.0);
        for (long price = topPrice; price >= bottomPrice; price -= tickSz) {
            double y = view.priceToY(price, centreP);
            gc.strokeLine(0, y, w, y);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 6: Bid bars (Section 5.3 step 6; Section 5.5)
        //         bar grows LEFT from the price zone; width ∝ qty/maxBidQty
        // ══════════════════════════════════════════════════════════════════
        gc.setFill(colorScheme.bidBar);
        for (int i = 0; i < snap.bidPrices().length; i++) {
            long p = snap.bidPrices()[i];
            if (p < bottomPrice) break;
            if (p > topPrice) continue;
            // Convert to double only at the final bar-width calculation
            double barWidth = (double) snap.bidQtys()[i] / (double) maxBidQty * bidZoneW;
            double barX     = bidZoneW - barWidth;
            double barY     = view.priceToY(p, centreP);
            gc.fillRect(barX, barY + 1, barWidth, rowH - 2);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 7: Ask bars (Section 5.3 step 7; Section 5.5)
        //         bar grows RIGHT from the price zone; width ∝ qty/maxAskQty
        // ══════════════════════════════════════════════════════════════════
        gc.setFill(colorScheme.askBar);
        for (int i = 0; i < snap.askPrices().length; i++) {
            long p = snap.askPrices()[i];
            if (p > topPrice) continue;
            if (p < bottomPrice) break;
            double barWidth = (double) snap.askQtys()[i] / (double) maxAskQty * askZoneW;
            double barY     = view.priceToY(p, centreP);
            gc.fillRect(askZoneX, barY + 1, barWidth, rowH - 2);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 8: Price text (Section 5.3 step 8; Section 5.6)
        // ══════════════════════════════════════════════════════════════════
        double priceFontSz = Math.clamp(rowH * 0.55, 9.0, 14.0);
        gc.setFont(Font.font(priceFontSz));          // set once per frame, outside loop
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (long price = topPrice; price >= bottomPrice; price -= tickSz) {
            double textY = view.priceToY(price, centreP) + rowH / 2.0;
            boolean inSpread = bestBid >= 0 && bestAsk >= 0
                               && price > bestBid && price < bestAsk;

            if (price == bestBid) {
                gc.setFill(colorScheme.bestBidText);
            } else if (price == bestAsk) {
                gc.setFill(colorScheme.bestAskText);
            } else if (inSpread) {
                gc.setFill(colorScheme.spreadPriceText);
            } else {
                gc.setFill(colorScheme.priceText);
            }
            gc.fillText(formatPrice(price, spec), priceZoneCentX, textY);
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 9: Qty text (Section 5.3 step 9; Section 5.7)
        //         skipped entirely when rowH < 14px — rows too compressed
        // ══════════════════════════════════════════════════════════════════
        if (rowH >= 14.0) {
            double qtyFontSz = Math.clamp(rowH * 0.45, 8.0, 11.0);
            gc.setFont(Font.font(qtyFontSz));        // set once, outside loops
            gc.setFill(colorScheme.qtyText);

            gc.setTextAlign(TextAlignment.RIGHT);
            for (int i = 0; i < snap.bidPrices().length; i++) {
                long p = snap.bidPrices()[i];
                if (p < bottomPrice) break;
                if (p > topPrice) continue;
                double rowCentreY = view.priceToY(p, centreP) + rowH / 2.0;
                gc.fillText(formatQty(snap.bidQtys()[i], spec), bidZoneW - 4, rowCentreY);
            }

            gc.setTextAlign(TextAlignment.LEFT);
            for (int i = 0; i < snap.askPrices().length; i++) {
                long p = snap.askPrices()[i];
                if (p > topPrice) continue;
                if (p < bottomPrice) break;
                double rowCentreY = view.priceToY(p, centreP) + rowH / 2.0;
                gc.fillText(formatQty(snap.askQtys()[i], spec), askZoneX + 4, rowCentreY);
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Step 10: Panel divider line (Section 5.3 step 10)
        //          1px vertical line at the right edge of the canvas
        // ══════════════════════════════════════════════════════════════════
        gc.setStroke(colorScheme.panelDivider);
        gc.setLineWidth(1.0);
        gc.strokeLine(w - 1, 0, w - 1, h);
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
