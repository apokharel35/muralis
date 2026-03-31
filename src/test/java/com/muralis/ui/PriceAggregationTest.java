package com.muralis.ui;

import java.util.Map;

import com.muralis.model.InstrumentSpec;
import com.muralis.provider.ProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceAggregationTest {

    // ── bucketPrice ──────────────────────────────────────────────────────────

    @Test
    void bucketPrice_midOfBucket_roundsDown() {
        assertThat(PriceAggregation.bucketPrice(6708350L, 100L)).isEqualTo(6708300L);
    }

    @Test
    void bucketPrice_nearTopOfBucket_roundsDown() {
        assertThat(PriceAggregation.bucketPrice(6708399L, 100L)).isEqualTo(6708300L);
    }

    @Test
    void bucketPrice_exactBoundary_returnsItself() {
        assertThat(PriceAggregation.bucketPrice(6708400L, 100L)).isEqualTo(6708400L);
    }

    @Test
    void bucketPrice_lowerBoundary_returnsItself() {
        assertThat(PriceAggregation.bucketPrice(6708300L, 100L)).isEqualTo(6708300L);
    }

    // ── bucketQtySum ─────────────────────────────────────────────────────────

    @Test
    void bucketQtySum_rangeHalfInclusive_sumsCorrectly() {
        // prices=[100,150,200,250], qtys=[10,20,30,40]
        // bucket [100,200): 100→10, 150→20 included; 200 and 250 excluded
        long[] prices = {100L, 150L, 200L, 250L};
        long[] qtys   = {10L,  20L,  30L,  40L};
        assertThat(PriceAggregation.bucketQtySum(100L, 100L, prices, qtys)).isEqualTo(30L);
    }

    // ── bucketQtyMax ─────────────────────────────────────────────────────────

    @Test
    void bucketQtyMax_rangeHalfInclusive_returnsMax() {
        // Same arrays, bucket [100,200): max of qtys 10 and 20 = 20
        long[] prices = {100L, 150L, 200L, 250L};
        long[] qtys   = {10L,  20L,  30L,  40L};
        assertThat(PriceAggregation.bucketQtyMax(100L, 100L, prices, qtys)).isEqualTo(20L);
    }

    // ── bucketDeltaSum ───────────────────────────────────────────────────────

    @Test
    void bucketDeltaSum_iteratesByNativeTick_sumsInRange() {
        // map={100→5, 101→-3, 200→10}, nativeTick=1, bucket [100,200)
        // sums 100→5 and 101→-3; 200 is outside range → total = 2
        Map<Long, Long> map = Map.of(100L, 5L, 101L, -3L, 200L, 10L);
        assertThat(PriceAggregation.bucketDeltaSum(100L, 100L, map, 1L)).isEqualTo(2L);
    }

    // ── computeAggregationLevels — BTCUSDT ───────────────────────────────────

    @Test
    void computeAggregationLevels_btcusdt_firstElementIsOne() {
        InstrumentSpec spec = new InstrumentSpec(
            "BTCUSDT", 2, 1L, 3, 1L, "USDT", ProviderType.BINANCE_FUTURES);
        int[] levels = PriceAggregation.computeAggregationLevels(spec);
        assertThat(levels[0]).isEqualTo(1);
    }

    @Test
    void computeAggregationLevels_btcusdt_contains100() {
        // tpr=100 produces effectiveTickSize=100 → 1.00 display step
        InstrumentSpec spec = new InstrumentSpec(
            "BTCUSDT", 2, 1L, 3, 1L, "USDT", ProviderType.BINANCE_FUTURES);
        int[] levels = PriceAggregation.computeAggregationLevels(spec);
        assertThat(levels).contains(100);
    }

    @Test
    void computeAggregationLevels_btcusdt_contains1000() {
        // tpr=1000 produces effectiveTickSize=1000 → 10.00 display step
        InstrumentSpec spec = new InstrumentSpec(
            "BTCUSDT", 2, 1L, 3, 1L, "USDT", ProviderType.BINANCE_FUTURES);
        int[] levels = PriceAggregation.computeAggregationLevels(spec);
        assertThat(levels).contains(1000);
    }

    // ── computeAggregationLevels — ES (tickSize=25) ──────────────────────────

    @Test
    void computeAggregationLevels_es_firstElementIsOne() {
        InstrumentSpec spec = new InstrumentSpec(
            "ES", 2, 25L, 0, 1L, "USD", ProviderType.CME_RITHMIC);
        int[] levels = PriceAggregation.computeAggregationLevels(spec);
        assertThat(levels[0]).isEqualTo(1);
    }

    @Test
    void computeAggregationLevels_es_contains4() {
        // tpr=4 produces effectiveTickSize=100 → 1.00 display step (4 × 0.25)
        InstrumentSpec spec = new InstrumentSpec(
            "ES", 2, 25L, 0, 1L, "USD", ProviderType.CME_RITHMIC);
        int[] levels = PriceAggregation.computeAggregationLevels(spec);
        assertThat(levels).contains(4);
    }
}
