package com.muralis.engine;

import com.muralis.model.AggressorSide;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates per-price aggressor volume from NormalizedTrades.
 * Engine-thread only — no synchronization.
 */
class DeltaAccumulator {

    // key = price (fixed-point long), value = [buyVolume, sellVolume]
    private final HashMap<Long, long[]> priceDeltas;

    DeltaAccumulator() {
        this.priceDeltas = new HashMap<>();
    }

    void accumulate(long price, long qty, AggressorSide side) {
        long[] volumes = priceDeltas.computeIfAbsent(price, k -> new long[2]);
        if (side == AggressorSide.BUY) {
            volumes[0] += qty;   // index 0 = buy volume
        } else {
            volumes[1] += qty;   // index 1 = sell volume
        }
    }

    long getDelta(long price) {
        long[] volumes = priceDeltas.get(price);
        if (volumes == null) return 0L;
        return volumes[0] - volumes[1];  // positive = net buying
    }

    long getMaxAbsDelta() {
        long max = 0L;
        for (long[] volumes : priceDeltas.values()) {
            long delta = Math.abs(volumes[0] - volumes[1]);
            if (delta > max) max = delta;
        }
        return max;
    }

    Map<Long, long[]> getSnapshot() {
        return Collections.unmodifiableMap(priceDeltas);
    }

    void clear() {
        priceDeltas.clear();
    }
}
