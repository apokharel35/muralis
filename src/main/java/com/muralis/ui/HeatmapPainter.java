package com.muralis.ui;

import com.muralis.engine.HeatmapBuffer;
import com.muralis.engine.HeatmapColumn;
import com.muralis.engine.RenderConfig;
import com.muralis.engine.RenderSnapshot;
import com.muralis.engine.TradeBlip;
import com.muralis.model.AggressorSide;
import com.muralis.model.InstrumentSpec;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

class HeatmapPainter {

    private static final double MIN_CELL_WIDTH = 1.0;

    private final Canvas       canvas;
    private final LadderView   view;
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

        // Pass 1 — determine visible time range
        int writeIndex = buffer.getWriteIndex();
        HeatmapColumn newest = buffer.getColumn(writeIndex - 1);
        if (newest == null) return;
        long newestTs     = newest.timestamp();
        long timeWindowMs = (long) renderConfig.heatmapTimeWindowSec() * 1000L;

        // Pass 2 — find maxQty across all visible cells
        long maxQty = 0L;
        for (int i = writeIndex - 1; i >= 0; i--) {
            HeatmapColumn col = buffer.getColumn(i);
            if (col == null) break;
            if (col.timestamp() < newestTs - timeWindowMs) break;
            for (long qty : col.quantities()) {
                if (qty > maxQty) maxQty = qty;
            }
        }
        if (maxQty == 0L) return;

        // Pass 3 — paint liquidity cells
        for (int i = writeIndex - 1; i >= 0; i--) {
            HeatmapColumn col = buffer.getColumn(i);
            if (col == null) break;
            if (col.timestamp() < newestTs - timeWindowMs) break;

            double x = timeToX(col.timestamp(), newestTs, timeWindowMs, panelWidth);
            if (x < 0) continue;

            long nextColTs;
            if (i == writeIndex - 1) {
                nextColTs = newestTs + 100L;
            } else {
                HeatmapColumn nextCol = buffer.getColumn(i + 1);
                nextColTs = (nextCol != null) ? nextCol.timestamp() : newestTs + 100L;
            }
            double xNext   = timeToX(nextColTs, newestTs, timeWindowMs, panelWidth);
            double colWidth = Math.max(xNext - x, MIN_CELL_WIDTH);

            long[]  prices     = col.prices();
            long[]  quantities = col.quantities();
            double  rowH       = view.rowHeightPx();

            for (int j = 0; j < prices.length; j++) {
                long qty = quantities[j];
                if (qty == 0L) continue;

                double y = view.priceToY(prices[j], view.centrePrice());
                if (y < 0 || y > panelHeight) continue;

                Color cell = heatmapColor(qty, maxQty, renderConfig.heatmapIntensity(), colorScheme);
                if (cell == null) continue;

                gc.setFill(cell);
                gc.fillRect(x, y, colWidth, rowH);
            }
        }

        // Pass 4 — volume dots
        InstrumentSpec spec = snap.instrumentSpec();
        for (int i = writeIndex - 1; i >= 0; i--) {
            HeatmapColumn col = buffer.getColumn(i);
            if (col == null) break;
            if (col.timestamp() < newestTs - timeWindowMs) break;

            double x = timeToX(col.timestamp(), newestTs, timeWindowMs, panelWidth);
            if (x < 0) continue;

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

        // Pass 5 — BBO bid line
        int visibleColumnCount = (int)(timeWindowMs / 100L) + 2;
        int firstVisible       = Math.max(0, writeIndex - visibleColumnCount);
        if (renderConfig.bboLineEnabled()) {
            gc.setStroke(colorScheme.bboBid);
            gc.setLineWidth(1.0);
            gc.beginPath();
            boolean bidStarted = false;
            for (int i = firstVisible; i < writeIndex; i++) {
                HeatmapColumn col = buffer.getColumn(i);
                if (col == null) continue;
                if (col.timestamp() < newestTs - timeWindowMs) continue;
                if (col.bestBid() <= 0L) continue;

                double x = timeToX(col.timestamp(), newestTs, timeWindowMs, panelWidth);
                double y = view.priceToY(col.bestBid(), view.centrePrice());
                if (!bidStarted) { gc.moveTo(x, y); bidStarted = true; }
                else             { gc.lineTo(x, y); }
            }
            if (bidStarted) gc.stroke();

            // Pass 6 — BBO ask line
            gc.setStroke(colorScheme.bboAsk);
            gc.beginPath();
            boolean askStarted = false;
            for (int i = firstVisible; i < writeIndex; i++) {
                HeatmapColumn col = buffer.getColumn(i);
                if (col == null) continue;
                if (col.timestamp() < newestTs - timeWindowMs) continue;
                if (col.bestAsk() <= 0L) continue;

                double x = timeToX(col.timestamp(), newestTs, timeWindowMs, panelWidth);
                double y = view.priceToY(col.bestAsk(), view.centrePrice());
                if (!askStarted) { gc.moveTo(x, y); askStarted = true; }
                else             { gc.lineTo(x, y); }
            }
            if (askStarted) gc.stroke();
        }
    }

    private double bubbleDiameter(long qty, InstrumentSpec spec) {
        double qtyDouble  = (double) qty / Math.pow(10, spec.qtyScale());
        double logQty     = Math.log10(Math.max(qtyDouble, 0.001) + 1.0);
        double MIN_DIAMETER = 4.0;
        double MAX_DIAMETER = Math.min(view.rowHeightPx() * 2.0, 40.0);
        double normalised = logQty / Math.log10(11.0);
        return MIN_DIAMETER + normalised * (MAX_DIAMETER - MIN_DIAMETER);
    }

    private double timeToX(long timestamp, long newestTs,
                           long timeWindowMs, double panelWidth) {
        long age = newestTs - timestamp;
        if (age < 0 || age > timeWindowMs) return -1.0;
        double ratio = 1.0 - ((double) age / timeWindowMs);
        return ratio * panelWidth;
    }

    private Color heatmapColor(long qty, long maxQty,
                               double intensity, ColorScheme scheme) {
        double ratio = (double) qty / maxQty;
        double alpha = ratio * intensity;
        if (alpha < 0.02) return null;

        Color base;
        if (ratio <= 0.25) {
            double t = ratio / 0.25;
            base = interpolate(scheme.heatmapBackground, scheme.heatmapThin, t);
        } else if (ratio <= 0.60) {
            double t = (ratio - 0.25) / 0.35;
            base = interpolate(scheme.heatmapThin, scheme.heatmapMid, t);
        } else if (ratio <= 0.85) {
            double t = (ratio - 0.60) / 0.25;
            base = interpolate(scheme.heatmapMid, scheme.heatmapThick, t);
        } else {
            double t = (ratio - 0.85) / 0.15;
            base = interpolate(scheme.heatmapThick, scheme.heatmapMax, t);
        }

        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                         Math.clamp(alpha, 0.0, 1.0));
    }

    private Color interpolate(Color a, Color b, double t) {
        return new Color(
            a.getRed()     + (b.getRed()     - a.getRed())     * t,
            a.getGreen()   + (b.getGreen()   - a.getGreen())   * t,
            a.getBlue()    + (b.getBlue()    - a.getBlue())    * t,
            a.getOpacity() + (b.getOpacity() - a.getOpacity()) * t
        );
    }
}
