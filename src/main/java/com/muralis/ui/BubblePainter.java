package com.muralis.ui;

import com.muralis.engine.RenderSnapshot;
import com.muralis.engine.TradeBlip;
import com.muralis.model.AggressorSide;
import com.muralis.model.InstrumentSpec;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.math.BigDecimal;
import java.util.List;

class BubblePainter {

    // Section 6.1: fixed panel width
    private static final double PANEL_WIDTH = 280.0;

    private final Canvas     canvas;
    private final LadderView view;
    ColorScheme colorScheme;   // package-private — LadderCanvas swaps on theme toggle

    // Updated by LadderCanvas when the decay slider changes; default 5s per RenderConfig spec
    long decayMs = 5_000L;

    BubblePainter(Canvas canvas, LadderView view, ColorScheme colorScheme) {
        this.canvas      = canvas;
        this.view        = view;
        this.colorScheme = colorScheme;
    }

    void paint(RenderSnapshot snap) {
        GraphicsContext gc      = canvas.getGraphicsContext2D();
        double          w       = canvas.getWidth();
        double          h       = canvas.getHeight();
        InstrumentSpec  spec    = snap.instrumentSpec();
        long            centreP = view.centrePrice();

        // ── Step 1: Fill panel background (Section 6.6, step 1) ───────────
        gc.setFill(colorScheme.panelBackground);
        gc.fillRect(0, 0, w, h);

        // ── Step 2: Panel divider — 1px vertical line at left edge ────────
        gc.setStroke(colorScheme.panelDivider);
        gc.setLineWidth(1.0);
        gc.strokeLine(0, 0, 0, h);

        // ── Step 3: Draw bubbles, newest first (Section 6.6, step 3) ──────
        List<TradeBlip> trades = snap.recentTrades();
        for (int i = trades.size() - 1; i >= 0; i--) {
            TradeBlip blip = trades.get(i);

            // (a) Compute alpha; skip if < 0.02 (Section 6.3)
            double alpha = bubbleAlpha(blip, decayMs);
            if (alpha < 0.02) continue;

            // (b) Compute geometry
            double diameter = bubbleDiameter(blip.qty(), spec);
            double radius   = diameter / 2.0;
            double x        = bubbleX(blip, decayMs, PANEL_WIDTH, diameter);
            // Section 6.5: same vertical mapping as ladder; centre on price row
            double y        = view.priceToY(blip.price(), centreP)
                              + view.rowHeightPx() / 2.0;

            // (c) Colors with alpha applied (Section 6.6, step c)
            Color fill, stroke;
            if (blip.aggressorSide() == AggressorSide.BUY) {
                fill   = colorScheme.buyBubbleFill.deriveColor(0, 1, 1, alpha);
                stroke = colorScheme.buyBubbleStroke.deriveColor(0, 1, 1, alpha);
            } else {
                fill   = colorScheme.sellBubbleFill.deriveColor(0, 1, 1, alpha);
                stroke = colorScheme.sellBubbleStroke.deriveColor(0, 1, 1, alpha);
            }

            // (d) Fill oval (Section 6.6, step d)
            gc.setFill(fill);
            gc.fillOval(x - radius, y - radius, diameter, diameter);

            // (e) Stroke oval (Section 6.6, step e)
            gc.setStroke(stroke);
            gc.setLineWidth(1.0);
            gc.strokeOval(x - radius, y - radius, diameter, diameter);

            // (f) Qty label inside bubble when diameter >= 18px (Section 6.6, step f)
            if (diameter >= 18.0) {
                gc.setFill(colorScheme.bubbleQtyText.deriveColor(0, 1, 1, alpha));
                gc.setFont(Font.font(Math.clamp(diameter * 0.3, 7.0, 11.0)));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.CENTER);
                gc.fillText(formatQtyShort(blip.qty(), spec), x, y);
            }
        }
    }

    // Section 6.2 — logarithmic sizing; long qty converted to double here only
    private double bubbleDiameter(long qty, InstrumentSpec spec) {
        double qtyDouble   = (double) qty / Math.pow(10, spec.qtyScale());
        double logQty      = Math.log10(Math.max(qtyDouble, 0.001) + 1.0);
        double maxDiameter = Math.min(view.rowHeightPx() * 2.5, 60.0);
        double normalised  = logQty / Math.log10(11.0);
        return 6.0 + normalised * (maxDiameter - 6.0);
    }

    // Section 6.3 — linear decay using receivedTs (never exchangeTs)
    private double bubbleAlpha(TradeBlip blip, long decayWindowMs) {
        long   ageMs = System.currentTimeMillis() - blip.receivedTs();
        double life  = 1.0 - ((double) ageMs / decayWindowMs);
        return Math.clamp(life, 0.0, 1.0);
    }

    // Section 6.4 — newest at left edge, oldest drifts right; uses receivedTs
    private double bubbleX(TradeBlip blip, long decayWindowMs,
                           double panelWidth, double diameter) {
        long   ageMs      = System.currentTimeMillis() - blip.receivedTs();
        double lifeRatio  = (double) ageMs / decayWindowMs;
        double maxDiameter = Math.min(view.rowHeightPx() * 2.5, 60.0);
        return lifeRatio * (panelWidth - maxDiameter);
    }

    // Section 6.6 — at most 4 decimal places; strip trailing zeros; plain notation
    private String formatQtyShort(long qty, InstrumentSpec spec) {
        String s = BigDecimal.valueOf(qty, spec.qtyScale())
                             .stripTrailingZeros()
                             .toPlainString();
        int dot = s.indexOf('.');
        if (dot >= 0 && s.length() - dot - 1 > 4) {
            s = s.substring(0, dot + 5);   // keep 4 decimal places
        }
        return s;
    }
}
