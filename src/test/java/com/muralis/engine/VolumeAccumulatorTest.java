package com.muralis.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolumeAccumulatorTest {

    private VolumeAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new VolumeAccumulator();
    }

    @Test
    void threeTradesAtSamePriceAccumulatesTotal() {
        long price = 5000000L;
        accumulator.accumulate(price, 100L);
        accumulator.accumulate(price, 200L);
        accumulator.accumulate(price, 150L);
        assertThat(accumulator.getVolume(price)).isEqualTo(450L);
    }

    @Test
    void tradesAtDifferentPricesTrackedSeparately() {
        long priceA = 5000000L;
        long priceB = 5000100L;
        long priceC = 4999900L;
        accumulator.accumulate(priceA, 100L);
        accumulator.accumulate(priceB, 200L);
        accumulator.accumulate(priceC, 300L);
        assertThat(accumulator.getVolume(priceA)).isEqualTo(100L);
        assertThat(accumulator.getVolume(priceB)).isEqualTo(200L);
        assertThat(accumulator.getVolume(priceC)).isEqualTo(300L);
    }

    @Test
    void getMaxVolumeReturnsCorrectMaximum() {
        accumulator.accumulate(5000000L, 100L);
        accumulator.accumulate(5000100L, 500L);
        accumulator.accumulate(4999900L, 300L);
        assertThat(accumulator.getMaxVolume()).isEqualTo(500L);
    }

    @Test
    void getVolumeOnUntouchedPriceReturnsZero() {
        assertThat(accumulator.getVolume(9999999L)).isEqualTo(0L);
    }

    @Test
    void clearResetsEverythingToZero() {
        accumulator.accumulate(5000000L, 100L);
        accumulator.accumulate(5000100L, 200L);
        accumulator.clear();
        assertThat(accumulator.getVolume(5000000L)).isEqualTo(0L);
        assertThat(accumulator.getVolume(5000100L)).isEqualTo(0L);
        assertThat(accumulator.getMaxVolume()).isEqualTo(0L);
    }

    @Test
    void getSnapshotReturnsImmutableMap() {
        accumulator.accumulate(5000000L, 100L);
        Map<Long, Long> snapshot = accumulator.getSnapshot();
        assertThat(snapshot).containsEntry(5000000L, 100L);
        assertThatThrownBy(() -> snapshot.put(5000000L, 999L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getMaxVolumeOnEmptyAccumulatorReturnsZero() {
        assertThat(accumulator.getMaxVolume()).isEqualTo(0L);
    }
}
