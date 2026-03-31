package com.muralis.ui;

class LadderView {

    private static final double MIN_ROW_PX = 10.0;
    private static final double MAX_ROW_PX = 60.0;

    private final double canvasHeight;

    double rowHeightPx;
    int    visibleRows;
    double scrollOffsetTicks;
    long   centrePrice;
    long   tickSize;

    LadderView(double canvasHeight) {
        this.canvasHeight      = canvasHeight;
        this.rowHeightPx       = 10.0;
        this.scrollOffsetTicks = 0.0;
        this.centrePrice       = 0L;
        this.tickSize          = 1L;
        this.visibleRows       = computeVisibleRows();
    }

    // Update the reference price used for Y mapping.
    // Called every frame by auto-centre logic; does NOT reset scrollOffsetTicks.
    void centreOn(long price) {
        centrePrice  = price;
        visibleRows  = computeVisibleRows();
    }

    // Add deltaTicks to the manual scroll offset (positive = scroll down toward lower prices).
    void adjustScroll(double deltaTicks) {
        scrollOffsetTicks += deltaTicks;
    }

    // Reset manual scroll; next frame auto-centre resumes.
    void resetScroll() {
        scrollOffsetTicks = 0.0;
    }

    // Convert a price level to a canvas Y coordinate.
    // Higher prices → lower Y (top of screen).
    // referencePrice maps to canvasHeight/2 when scrollOffsetTicks == 0.
    double priceToY(long price, long referencePrice) {
        double tickOffset = (double)(referencePrice - price) / tickSize - scrollOffsetTicks;
        return canvasHeight / 2.0 + tickOffset * rowHeightPx;
    }

    // Inverse of priceToY: canvas Y → nearest price level (snapped to tick grid).
    long yToPrice(double y, long referencePrice) {
        double adjustedOffset = (y - canvasHeight / 2.0) / rowHeightPx;
        long tickCount = Math.round(adjustedOffset + scrollOffsetTicks);
        return referencePrice - tickCount * tickSize;
    }

    // Adjust row height by deltaRowHeight, clamped to [MIN_ROW_PX, MAX_ROW_PX].
    // Recalculates visibleRows after the change.
    void adjustZoom(double deltaRowHeight) {
        rowHeightPx = Math.clamp(rowHeightPx + deltaRowHeight, MIN_ROW_PX, MAX_ROW_PX);
        visibleRows = computeVisibleRows();
    }

    int visibleRows() {
        return visibleRows;
    }

    double rowHeightPx() {
        return rowHeightPx;
    }

    long centrePrice() {
        return centrePrice;
    }

    double scrollOffsetTicks() {
        return scrollOffsetTicks;
    }

    // Section 5.2: ceil(canvasHeight / rowHeightPx) + 2 for partial top/bottom rows
    private int computeVisibleRows() {
        return (int) Math.ceil(canvasHeight / rowHeightPx) + 2;
    }
}
