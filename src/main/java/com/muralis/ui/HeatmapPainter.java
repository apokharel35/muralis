package com.muralis.ui;

import com.muralis.engine.HeatmapBuffer;
import com.muralis.engine.HeatmapColumn;
import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.engine.TradeBlip;
import com.muralis.model.AggressorSide;
import com.muralis.model.InstrumentSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

class HeatmapPainter {

    private static final double MIN_CELL_WIDTH = 1.0;
    private static final double RIGHT_MARGIN   = 12.0;

    private final Canvas       canvas;
    final LadderView            view;    // package-private — checked by HeatmapCanvas for rebuild
    ColorScheme                colorScheme;   // package-private — swapped on theme toggle
    private final RenderConfig renderConfig;

    HeatmapPainter(Canvas canvas, LadderView view,
                   ColorScheme colorScheme, RenderConfig renderConfig) {
        this.canvas       = canvas;
        this.view         = view;
        this.colorScheme  = colorScheme;
        this.renderConfig = renderConfig;
    }

    void paint(RenderSnapshot snap) {
        GraphicsContext gc         = canvas.getGraphicsContext2D();
        double          panelWidth = canvas.getWidth();
        double          panelHeight = canvas.getHeight();

        // Pass 0 — guard and background
        HeatmapBuffer buffer = snap.heatmapBuffer();
        if (buffer == null || buffer.getWriteIndex() == 0) {
            gc.setFill(colorScheme.heatmapBackground);
            gc.fillRect(0, 0, panelWidth, panelHeight);
            return;
        }
        if (!renderConfig.heatmapEnabled()) {
            gc.setFill(colorScheme.heatmapBackground);
            gc.fillRect(0, 0, panelWidth, panelHeight);
            return;
        }
        gc.setFill(colorScheme.heatmapBackground);
        gc.fillRect(0, 0, panelWidth, panelHeight);

        // Pass 1 — determine visible time range and build ordered column list
        int writeIndex = buffer.getWriteIndex();
        HeatmapColumn newest = buffer.getColumn(writeIndex - 1);
        if (newest == null) return;
        long newestTs     = newest.timestamp();
        long timeWindowMs = (long) renderConfig.heatmapTimeWindowSec() * 1000L;

        List<HeatmapColumn> visibleList = new ArrayList<>();
        for (int i = writeIndex - 1; i >= 0; i--) {
            HeatmapColumn c = buffer.getColumn(i);
            if (c == null) break;
            if (c.timestamp() < newestTs - timeWindowMs) break;
            visibleList.add(c);
        }
        Collections.reverse(visibleList); // oldest first

        // Pass 2 — find maxQty across visible cells in visible Y range
        long   maxQty  = 0L;
        double scanRowH = view.rowHeightPx();
        for (HeatmapColumn col : visibleList) {
            long[] scanPrices = col.prices();
            long[] scanQtys   = col.quantities();
            for (int j = 0; j < scanPrices.length; j++) {
                double y = view.priceToY(scanPrices[j], view.centrePrice());
                if (y < -scanRowH || y > panelHeight + scanRowH) continue;
                if (scanQtys[j] > maxQty) maxQty = scanQtys[j];
            }
        }
        if (maxQty == 0L) return;

        // Uniform column width — fills panel left-to-right as data accumulates
        int    n        = visibleList.size();
        double colWidth = n > 0 ? Math.max((panelWidth - RIGHT_MARGIN) / n, MIN_CELL_WIDTH)
                                : MIN_CELL_WIDTH;
        double rightEdge = panelWidth - RIGHT_MARGIN;

        // Pass 3 — paint liquidity cells (index-based X, newest at right edge)
        double rowH = view.rowHeightPx();
        for (int idx = 0; idx < n; idx++) {
            HeatmapColumn col = visibleList.get(idx);
            double x = rightEdge - (n - idx) * colWidth;
            if (x + colWidth < 0) continue;

            long[] prices     = col.prices();
            long[] quantities = col.quantities();

            for (int j = 0; j < prices.length; j++) {
                long qty = quantities[j];
                if (qty == 0L) continue;

                double y = view.priceToY(prices[j], view.centrePrice());
                if (y < -rowH || y > panelHeight + rowH) continue;

                Color cell = heatmapColor(qty, maxQty, renderConfig.heatmapIntensity(), colorScheme);
                if (cell == null) continue;

                gc.setFill(cell);
                gc.fillRect(x, y, colWidth, rowH);
            }
        }

        // Pass 4 — volume dots (same index-based X)
        InstrumentSpec spec = snap.instrumentSpec();
        for (int idx = 0; idx < n; idx++) {
            HeatmapColumn col = visibleList.get(idx);
            double x = rightEdge - (n - idx) * colWidth;
            if (x + colWidth < 0) continue;

            for (TradeBlip trade : col.trades()) {
                double y = view.priceToY(trade.price(), view.centrePrice());
                if (y < 0 || y > panelHeight) continue;

                double diameter = bubbleDiameter(trade.qty(), spec);
                double radius   = diameter / 2.0;
                Color dotColor  = trade.aggressorSide() == AggressorSide.BUY
                                  ? colorScheme.buyBubbleFill
                                  : colorScheme.sellBubbleFill;
                gc.setFill(dotColor);
                gc.fillOval(x - radius, y - radius, diameter, diameter);
            }
        }

        // Pass 5 — BBO bid line (oldest to newest, same X formula)
        if (renderConfig.bboLineEnabled()) {
            gc.setStroke(colorScheme.bboBid);
            gc.setLineWidth(1.0);
            gc.beginPath();
            boolean bidStarted = false;
            for (int idx = 0; idx < n; idx++) {
                HeatmapColumn col = visibleList.get(idx);
                if (col.bestBid() <= 0L) continue;
                double x = rightEdge - (n - idx) * colWidth;
                double y = view.priceToY(col.bestBid(), view.centrePrice());
                if (!bidStarted) { gc.moveTo(x, y); bidStarted = true; }
                else             { gc.lineTo(x, y); }
            }
            if (bidStarted) gc.stroke();

            // Pass 6 — BBO ask line
            gc.setStroke(colorScheme.bboAsk);
            gc.beginPath();
            boolean askStarted = false;
            for (int idx = 0; idx < n; idx++) {
                HeatmapColumn col = visibleList.get(idx);
                if (col.bestAsk() <= 0L) continue;
                double x = rightEdge - (n - idx) * colWidth;
                double y = view.priceToY(col.bestAsk(), view.centrePrice());
                if (!askStarted) { gc.moveTo(x, y); askStarted = true; }
                else             { gc.lineTo(x, y); }
            }
            if (askStarted) gc.stroke();
        }

        // Divider line — right edge of heatmap area
        gc.setStroke(colorScheme.heatmapBackground.brighter());
        gc.setLineWidth(1.0);
        gc.strokeLine(rightEdge, 0, rightEdge, panelHeight);
    }

    private double bubbleDiameter(long qty, InstrumentSpec spec) {
        double qtyDouble  = (double) qty / Math.pow(10, spec.qtyScale());
        double logQty     = Math.log10(Math.max(qtyDouble, 0.001) + 1.0);
        double MIN_DIAMETER = 4.0;
        double MAX_DIAMETER = Math.min(view.rowHeightPx() * 2.0, 40.0);
        double normalised = logQty / Math.log10(11.0);
        return MIN_DIAMETER + normalised * (MAX_DIAMETER - MIN_DIAMETER);
    }

    private Color heatmapColor(long qty, long maxQty,
                               double intensity, ColorScheme scheme) {
        double ratio = (double) qty / maxQty;
        if (ratio < 0.02) return null;

        Color base;
        if (ratio <= 0.15) {
            double t = ratio / 0.15;
            base = interpolate(scheme.heatmapBlack, scheme.heatmapDarkBlue, t);
        } else if (ratio <= 0.35) {
            double t = (ratio - 0.15) / 0.20;
            base = interpolate(scheme.heatmapDarkBlue, scheme.heatmapBlue, t);
        } else if (ratio <= 0.55) {
            double t = (ratio - 0.35) / 0.20;
            base = interpolate(scheme.heatmapBlue, scheme.heatmapWhite, t);
        } else if (ratio <= 0.75) {
            double t = (ratio - 0.55) / 0.20;
            base = interpolate(scheme.heatmapWhite, scheme.heatmapYellow, t);
        } else if (ratio <= 0.90) {
            double t = (ratio - 0.75) / 0.15;
            base = interpolate(scheme.heatmapYellow, scheme.heatmapOrange, t);
        } else {
            double t = (ratio - 0.90) / 0.10;
            base = interpolate(scheme.heatmapOrange, scheme.heatmapRed, t);
        }

        // Apply intensity to brightness via deriveColor:
        // intensity < 1.0 = darker, intensity > 1.0 = brighter
        // Alpha is fixed at the ratio value (not intensity-scaled)
        double alpha = Math.clamp(ratio, 0.02, 1.0);
        Color adjusted = base.deriveColor(0, 1.0, intensity, alpha);
        return adjusted;
    }

    private Color interpolate(Color a, Color b, double t) {
        double r  = Math.clamp(a.getRed()      + t * (b.getRed()      - a.getRed()),      0.0, 1.0);
        double g  = Math.clamp(a.getGreen()    + t * (b.getGreen()    - a.getGreen()),    0.0, 1.0);
        double bl = Math.clamp(a.getBlue()     + t * (b.getBlue()     - a.getBlue()),     0.0, 1.0);
        double al = Math.clamp(a.getOpacity()  + t * (b.getOpacity()  - a.getOpacity()),  0.0, 1.0);
        return new Color(r, g, bl, al);
    }
}
