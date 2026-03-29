package com.muralis.model;

public record NormalizedTrade(
    String        symbol,          // e.g. "BTCUSDT"
    long          tradeId,         // Exchange-assigned trade ID. Unique per session.
    long          price,           // Fixed-point. Scale = instrument priceScale.
    long          qty,             // Fixed-point. Scale = instrument qtyScale.
    AggressorSide aggressorSide,   // Derived from isBuyerMaker at parse time
    long          exchangeTs,      // T field from Binance trade event (ms)
    long          receivedTs       // System.currentTimeMillis() at receipt
) implements MarketEvent {
}
