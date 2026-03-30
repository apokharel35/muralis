package com.muralis.engine;

import com.muralis.model.OrderBookDelta;
import com.muralis.model.OrderBookSnapshot;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mutable bid/ask state. Owned exclusively by the engine thread.
 * No synchronisation is needed or permitted.
 */
class OrderBook {

    private final TreeMap<Long, Long> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Long> asks = new TreeMap<>();

    // -------------------------------------------------------------------------
    // Bulk apply methods (called by OrderBookEngine)
    // -------------------------------------------------------------------------

    void applySnapshot(OrderBookSnapshot snapshot) {
        clear();
        for (int i = 0; i < snapshot.bidPrices().length; i++) {
            long qty = snapshot.bidQtys()[i];
            if (qty > 0L) {
                bids.put(snapshot.bidPrices()[i], qty);
            }
        }
        for (int i = 0; i < snapshot.askPrices().length; i++) {
            long qty = snapshot.askQtys()[i];
            if (qty > 0L) {
                asks.put(snapshot.askPrices()[i], qty);
            }
        }
    }

    void applyDelta(OrderBookDelta delta) {
        for (int i = 0; i < delta.bidPrices().length; i++) {
            if (delta.bidQtys()[i] == 0L) {
                bids.remove(delta.bidPrices()[i]);
            } else {
                bids.put(delta.bidPrices()[i], delta.bidQtys()[i]);
            }
        }
        for (int i = 0; i < delta.askPrices().length; i++) {
            if (delta.askQtys()[i] == 0L) {
                asks.remove(delta.askPrices()[i]);
            } else {
                asks.put(delta.askPrices()[i], delta.askQtys()[i]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Individual level mutation (called by OrderBookEngine for direct access)
    // -------------------------------------------------------------------------

    void setBid(long price, long qty) {
        assert qty > 0L : "setBid called with qty=0. Use removeBid instead.";
        bids.put(price, qty);
    }

    void setAsk(long price, long qty) {
        assert qty > 0L : "setAsk called with qty=0. Use removeAsk instead.";
        asks.put(price, qty);
    }

    void removeBid(long price) {
        bids.remove(price);
    }

    void removeAsk(long price) {
        asks.remove(price);
    }

    // -------------------------------------------------------------------------
    // Point queries
    // -------------------------------------------------------------------------

    long getBidQty(long price) {
        return bids.getOrDefault(price, 0L);
    }

    long getAskQty(long price) {
        return asks.getOrDefault(price, 0L);
    }

    long bestBid() {
        return bids.isEmpty() ? -1L : bids.firstKey();
    }

    long bestAsk() {
        return asks.isEmpty() ? -1L : asks.firstKey();
    }

    // -------------------------------------------------------------------------
    // Depth queries — top-N parallel arrays
    // -------------------------------------------------------------------------

    long[] bidPrices(int depth) {
        int size = Math.min(depth, bids.size());
        long[] result = new long[size];
        int i = 0;
        for (long price : bids.keySet()) {
            if (i >= size) break;
            result[i++] = price;
        }
        return result;
    }

    long[] bidQtys(int depth) {
        int size = Math.min(depth, bids.size());
        long[] result = new long[size];
        int i = 0;
        for (long qty : bids.values()) {
            if (i >= size) break;
            result[i++] = qty;
        }
        return result;
    }

    long[] askPrices(int depth) {
        int size = Math.min(depth, asks.size());
        long[] result = new long[size];
        int i = 0;
        for (long price : asks.keySet()) {
            if (i >= size) break;
            result[i++] = price;
        }
        return result;
    }

    long[] askQtys(int depth) {
        int size = Math.min(depth, asks.size());
        long[] result = new long[size];
        int i = 0;
        for (long qty : asks.values()) {
            if (i >= size) break;
            result[i++] = qty;
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Full-book views for snapshot construction (live TreeMap entry set views)
    // Must only be called from the engine thread during buildSnapshot().
    // -------------------------------------------------------------------------

    Set<Map.Entry<Long, Long>> getBidsDescending() {
        return bids.entrySet();
    }

    Set<Map.Entry<Long, Long>> getAsksAscending() {
        return asks.entrySet();
    }

    // -------------------------------------------------------------------------
    // Depth counts
    // -------------------------------------------------------------------------

    int bidDepth() {
        return bids.size();
    }

    int askDepth() {
        return asks.size();
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    void clear() {
        bids.clear();
        asks.clear();
    }
}
