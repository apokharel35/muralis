package com.muralis.model;

public record OrderBookSnapshot(
    String symbol,          // e.g. "BTCUSDT"
    long   lastUpdateId,    // Sequence ID of this snapshot (from REST response)
    long   exchangeTs,      // Exchange-provided timestamp (ms)
    long   receivedTs,      // System.currentTimeMillis() at receipt
    long[] bidPrices,       // Parallel arrays. Index 0 = best bid.
    long[] bidQtys,         // Quantity at bidPrices[i]. 0 means empty level.
    long[] askPrices,       // Parallel arrays. Index 0 = best ask.
    long[] askQtys          // Quantity at askPrices[i]. 0 means empty level.
) implements MarketEvent {
}
