package com.muralis.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LadderViewTickSizeTest {

    // ── Test 1: tickSize=1 (BTCUSDT) round-trip ──────────────────────────

    @Test
    void priceToY_yToPrice_roundTrip_tickSize1() {
        LadderView view = new LadderView(800.0);
        view.tickSize = 1L;

        long refPrice = 6708342L;  // 67,083.42
        view.centreOn(refPrice);

        // Prices spaced 1 apart
        for (long offset = -20; offset <= 20; offset++) {
            long price = refPrice + offset;
            double y = view.priceToY(price, refPrice);
            long recovered = view.yToPrice(y, refPrice);
            assertThat(recovered).as("round-trip for price %d", price)
                    .isEqualTo(price);
        }
    }

    // ── Test 2: tickSize=25 (ES futures) round-trip ──────────────────────

    @Test
    void priceToY_yToPrice_roundTrip_tickSize25() {
        LadderView view = new LadderView(800.0);
        view.tickSize = 25L;

        long refPrice = 545050L;  // 5,450.50
        view.centreOn(refPrice);

        // Prices spaced 25 apart (valid ES ticks)
        for (int i = -10; i <= 10; i++) {
            long price = refPrice + i * 25L;
            double y = view.priceToY(price, refPrice);
            long recovered = view.yToPrice(y, refPrice);
            assertThat(recovered).as("round-trip for price %d (tick %d)", price, i)
                    .isEqualTo(price);
        }
    }

    // ── Test 3: tickSize=25 consecutive rows are 25 units apart ──────────

    @Test
    void consecutiveRows_tickSize25_spacedByTickSize() {
        LadderView view = new LadderView(800.0);
        view.tickSize = 25L;

        long refPrice = 545050L;
        view.centreOn(refPrice);

        // Two adjacent rows (one rowHeightPx apart) should differ by exactly tickSize
        double y0 = view.priceToY(refPrice, refPrice);
        double y1 = y0 + view.rowHeightPx();  // one row below

        long price0 = view.yToPrice(y0, refPrice);
        long price1 = view.yToPrice(y1, refPrice);

        assertThat(price0 - price1).isEqualTo(25L);

        // Also verify via priceToY: adjacent ticks should be exactly rowHeightPx apart
        double yA = view.priceToY(545050L, refPrice);
        double yB = view.priceToY(545025L, refPrice);
        assertThat(yB - yA).isEqualTo(view.rowHeightPx());
    }

    // ── Test 4: off-grid price maps differently from nearest tick ─────────

    @Test
    void offGridPrice_mapsDifferently_tickSize25() {
        LadderView view = new LadderView(800.0);
        view.tickSize = 25L;

        long refPrice = 545050L;
        view.centreOn(refPrice);

        long onGrid  = 545000L;
        long offGrid = 545012L;  // not on tick grid

        double yOn  = view.priceToY(onGrid, refPrice);
        double yOff = view.priceToY(offGrid, refPrice);

        // Off-grid price should map to a different Y than the on-grid price
        assertThat(yOff).isNotEqualTo(yOn);

        // yToPrice from on-grid Y should return the on-grid price
        assertThat(view.yToPrice(yOn, refPrice)).isEqualTo(onGrid);

        // yToPrice from off-grid Y should snap to nearest tick, not the off-grid value
        long snapped = view.yToPrice(yOff, refPrice);
        assertThat(snapped % 25L).as("snapped price must be on tick grid").isZero();
        assertThat(snapped).isNotEqualTo(offGrid);
    }
}
