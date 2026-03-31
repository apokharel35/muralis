package com.muralis.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.muralis.model.InstrumentSpec;

class PriceAggregation {

    private PriceAggregation() {}

    static long bucketPrice(long price, long effectiveTickSize) {
        return (price / effectiveTickSize) * effectiveTickSize;
    }

    static long bucketQtySum(long bucketStart, long effectiveTickSize,
                             long[] prices, long[] qtys) {
        long sum = 0L;
        long bucketEnd = bucketStart + effectiveTickSize;
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] >= bucketStart && prices[i] < bucketEnd) {
                sum += qtys[i];
            }
        }
        return sum;
    }

    static long bucketQtyMax(long bucketStart, long effectiveTickSize,
                             long[] prices, long[] qtys) {
        long max = 0L;
        long bucketEnd = bucketStart + effectiveTickSize;
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] >= bucketStart && prices[i] < bucketEnd) {
                max = Math.max(max, qtys[i]);
            }
        }
        return max;
    }

    static long bucketDeltaSum(long bucketStart, long effectiveTickSize,
                               Map<Long, Long> priceDeltaMap, long nativeTick) {
        long sum = 0L;
        for (long p = bucketStart; p < bucketStart + effectiveTickSize; p += nativeTick) {
            sum += priceDeltaMap.getOrDefault(p, 0L);
        }
        return sum;
    }

    static long bucketVolumeSum(long bucketStart, long effectiveTickSize,
                                Map<Long, Long> priceVolumeMap, long nativeTick) {
        long sum = 0L;
        for (long p = bucketStart; p < bucketStart + effectiveTickSize; p += nativeTick) {
            sum += priceVolumeMap.getOrDefault(p, 0L);
        }
        return sum;
    }

    static int[] computeAggregationLevels(InstrumentSpec spec) {
        long tick  = spec.tickSize();
        int  scale = spec.priceScale();
        long one   = (long) Math.pow(10, scale);

        List<Integer> levels = new ArrayList<>();
        levels.add(1);

        long[] targets = {
            one / 20,
            one / 10,
            one / 2,
            one,
            one * 5,
            one * 10,
            one * 50,
            one * 100,
            one * 500,
            one * 1000,
        };

        for (long target : targets) {
            if (target > tick) {
                int tpr = (int) (target / tick);
                if (tpr > 1 && tpr != levels.get(levels.size() - 1)) {
                    levels.add(tpr);
                }
            }
        }

        return levels.stream().mapToInt(Integer::intValue).toArray();
    }
}
