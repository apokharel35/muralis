package com.muralis.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fixed-capacity ring buffer of TradeBlip records for bubble rendering.
 * Owned exclusively by the engine thread. No synchronisation needed.
 */
class TradeBuffer {

    private static final int MAX_BLIPS = 500;

    private static final System.Logger LOG =
            System.getLogger(TradeBuffer.class.getName());

    private final TradeBlip[] buffer = new TradeBlip[MAX_BLIPS];
    private final Set<Long>   seenTradeIds = new HashSet<>();
    private int head = 0;
    private int size = 0;

    /**
     * Appends a blip. If the buffer is at capacity the oldest entry is
     * evicted first (ring overwrite). Caller must check
     * {@link #containsTradeId} before calling this method to avoid
     * duplicates; add() does not deduplicate by itself.
     */
    void add(TradeBlip blip) {
        if (size >= MAX_BLIPS) {
            TradeBlip evicted = buffer[head];
            seenTradeIds.remove(evicted.tradeId());
            buffer[head] = blip;
            head = (head + 1) % MAX_BLIPS;
            LOG.log(System.Logger.Level.DEBUG,
                    "TradeBuffer at capacity, evicting oldest blip");
        } else {
            buffer[(head + size) % MAX_BLIPS] = blip;
            size++;
        }
        seenTradeIds.add(blip.tradeId());
    }

    boolean containsTradeId(long tradeId) {
        return seenTradeIds.contains(tradeId);
    }

    /**
     * Returns all blips whose {@code receivedTs} falls within the decay
     * window. Iterates oldest-first; once the first live blip is found all
     * subsequent blips are guaranteed to be live (monotonic receivedTs).
     */
    List<TradeBlip> getActive(long decayMs) {
        long cutoffTs = System.currentTimeMillis() - decayMs;
        List<TradeBlip> result = new ArrayList<>();
        boolean collecting = false;
        for (int i = 0; i < size; i++) {
            TradeBlip blip = buffer[(head + i) % MAX_BLIPS];
            if (collecting) {
                result.add(blip);
            } else if (blip.receivedTs() >= cutoffTs) {
                collecting = true;
                result.add(blip);
            }
        }
        return Collections.unmodifiableList(result);
    }

    void clear() {
        Arrays.fill(buffer, null);
        seenTradeIds.clear();
        head = 0;
        size = 0;
    }
}
