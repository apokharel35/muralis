package com.muralis.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates total traded volume per price level from every NormalizedTrade.
 * Owned exclusively by the engine thread — no synchronization needed.
 */
class VolumeAccumulator {

    private final HashMap<Long, Long> priceVolumes = new HashMap<>();

    void accumulate(long price, long qty) {
        priceVolumes.merge(price, qty, Long::sum);
    }

    long getVolume(long price) {
        return priceVolumes.getOrDefault(price, 0L);
    }

    long getMaxVolume() {
        long max = 0L;
        for (long vol : priceVolumes.values()) {
            max = Math.max(max, vol);
        }
        return max;
    }

    Map<Long, Long> getSnapshot() {
        return Map.copyOf(priceVolumes);
    }

    void clear() {
        priceVolumes.clear();
    }
}
