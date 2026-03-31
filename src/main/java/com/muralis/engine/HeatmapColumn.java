package com.muralis.engine;

import java.util.Arrays;

/**
 * Immutable time slice of the order book for heatmap rendering.
 * Written by the engine thread; read by the UI thread via HeatmapBuffer.
 * Immutability guarantees thread safety without locking.
 */
public record HeatmapColumn(
        long         timestamp,    // System.currentTimeMillis() at capture
        long         bestBid,      // for BBO line rendering
        long         bestAsk,      // for BBO line rendering
        long[]       prices,       // ALL book levels, sorted ascending
        long[]       quantities,   // resting qty at each price (parallel)
        TradeBlip[]  trades        // trades that occurred in this time window
) {
    public HeatmapColumn {
        prices     = Arrays.copyOf(prices, prices.length);
        quantities = Arrays.copyOf(quantities, quantities.length);
        trades     = Arrays.copyOf(trades, trades.length);
    }
}
