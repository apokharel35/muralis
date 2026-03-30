package com.muralis.engine;

import com.muralis.model.AggressorSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaAccumulatorTest {

    private DeltaAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new DeltaAccumulator();
    }

    @Test
    void threeBuysOfHundredGivesPositiveDelta() {
        long price = 5000000L;
        accumulator.accumulate(price, 100L, AggressorSide.BUY);
        accumulator.accumulate(price, 100L, AggressorSide.BUY);
        accumulator.accumulate(price, 100L, AggressorSide.BUY);
        assertThat(accumulator.getDelta(price)).isEqualTo(300L);
    }

    @Test
    void twoSellsOfFiftyReducesDelta() {
        long price = 5000000L;
        accumulator.accumulate(price, 100L, AggressorSide.BUY);
        accumulator.accumulate(price, 100L, AggressorSide.BUY);
        accumulator.accumulate(price, 100L, AggressorSide.BUY);
        accumulator.accumulate(price, 50L, AggressorSide.SELL);
        accumulator.accumulate(price, 50L, AggressorSide.SELL);
        // 300 buy - 100 sell = 200
        assertThat(accumulator.getDelta(price)).isEqualTo(200L);
    }

    @Test
    void getMaxAbsDeltaReturnsCorrectMaxAcrossMultiplePrices() {
        long priceA = 5000000L;
        long priceB = 5000100L;
        long priceC = 4999900L;
        // priceA: 500 buy - 0 sell = 500
        accumulator.accumulate(priceA, 500L, AggressorSide.BUY);
        // priceB: 100 buy - 300 sell = -200, abs = 200
        accumulator.accumulate(priceB, 100L, AggressorSide.BUY);
        accumulator.accumulate(priceB, 300L, AggressorSide.SELL);
        // priceC: 0 buy - 50 sell = -50, abs = 50
        accumulator.accumulate(priceC, 50L, AggressorSide.SELL);

        assertThat(accumulator.getMaxAbsDelta()).isEqualTo(500L);
    }

    @Test
    void getDeltaOnUntouchedPriceReturnsZero() {
        assertThat(accumulator.getDelta(9999999L)).isEqualTo(0L);
    }

    @Test
    void clearResetsEverythingToZero() {
        long price = 5000000L;
        accumulator.accumulate(price, 200L, AggressorSide.BUY);
        accumulator.accumulate(price, 100L, AggressorSide.SELL);

        accumulator.clear();

        assertThat(accumulator.getDelta(price)).isEqualTo(0L);
        assertThat(accumulator.getMaxAbsDelta()).isEqualTo(0L);
        assertThat(accumulator.getSnapshot()).isEmpty();
    }
}
