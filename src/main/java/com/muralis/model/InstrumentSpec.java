package com.muralis.model;

import com.muralis.provider.ProviderType;

public record InstrumentSpec(
    String       symbol,       // Canonical symbol, e.g. "BTCUSDT"
    int          priceScale,   // Decimal places in price. BTC=2, ES=2, NQ=2.
    long         tickSize,     // Minimum price increment in fixed-point.
    int          qtyScale,     // Decimal places in quantity. Crypto=8, Futures=0.
    long         minQty,       // Minimum order quantity in fixed-point.
    String       currency,     // Settlement currency. e.g. "USDT", "USD"
    ProviderType provider      // Which provider supplies this instrument
) {
}
