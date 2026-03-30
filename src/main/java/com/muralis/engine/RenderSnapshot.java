package com.muralis.engine;

import com.muralis.model.ConnectionState;
import com.muralis.model.InstrumentSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Immutable value object written by the engine thread and read by the UI thread.
 * Passed via AtomicReference — no locking required.
 * A null reference means the engine has not yet produced its first snapshot.
 */
public record RenderSnapshot(
        String          symbol,
        long            exchangeTs,
        long[]          bidPrices,
        long[]          bidQtys,
        long[]          askPrices,
        long[]          askQtys,
        List<TradeBlip>  recentTrades,
        Map<Long, Long>  priceDeltaMap,
        long             maxAbsDelta,
        ConnectionState  connectionState,
        InstrumentSpec   instrumentSpec
) {
    /**
     * Compact constructor makes defensive copies of all mutable fields so
     * the engine cannot mutate a snapshot after handing it to the UI thread.
     */
    public RenderSnapshot {
        bidPrices    = Arrays.copyOf(bidPrices,    bidPrices.length);
        bidQtys      = Arrays.copyOf(bidQtys,      bidQtys.length);
        askPrices    = Arrays.copyOf(askPrices,    askPrices.length);
        askQtys      = Arrays.copyOf(askQtys,      askQtys.length);
        recentTrades = List.copyOf(recentTrades);
        priceDeltaMap = Map.copyOf(priceDeltaMap);
    }
}
