package com.muralis.model;

public record OrderBookDelta(
    String symbol,          // e.g. "BTCUSDT"
    long   firstUpdateId,   // U field from Binance — first update ID in batch
    long   finalUpdateId,   // u field from Binance — last update ID in batch
    long   exchangeTs,      // E field from Binance message (event time, ms)
    long   receivedTs,      // System.currentTimeMillis() at receipt
    long[] bidPrices,       // Changed bid price levels (fixed-point)
    long[] bidQtys,         // New quantity at bidPrices[i]. 0 = remove level.
    long[] askPrices,       // Changed ask price levels (fixed-point)
    long[] askQtys          // New quantity at askPrices[i]. 0 = remove level.
) implements MarketEvent {
}
