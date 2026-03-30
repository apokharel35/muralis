package com.muralis.engine;

import com.muralis.model.AggressorSide;

/**
 * Lightweight trade record for bubble rendering.
 * Immutable by virtue of being a record.
 * Written by the engine thread; read by the UI thread via RenderSnapshot.
 */
public record TradeBlip(
        long          tradeId,       // For duplicate detection in TradeBuffer
        long          price,         // Fixed-point, scale = instrument priceScale
        long          qty,           // Fixed-point, scale = instrument qtyScale
        AggressorSide aggressorSide,
        long          exchangeTs,    // Exchange-provided event time (ms)
        long          receivedTs     // Local receipt time — used for bubble decay
) {}
