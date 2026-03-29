package com.muralis.provider;

import com.muralis.model.ConnectionEvent;
import com.muralis.model.NormalizedTrade;
import com.muralis.model.OrderBookDelta;
import com.muralis.model.OrderBookSnapshot;

public interface MarketDataListener {

    /**
     * Called when a full order book snapshot is available.
     * The engine must reset all order book state when this is received.
     */
    void onSnapshot(OrderBookSnapshot snapshot);

    /**
     * Called for each incremental order book update.
     * Guaranteed to arrive after onSnapshot() in each connect cycle.
     */
    void onDelta(OrderBookDelta delta);

    /**
     * Called for each matched trade.
     * May arrive before or after onSnapshot() — see SPEC-ingestion.md
     * Section 3.1 Step 1 (trades bypass the pre-buffer).
     */
    void onTrade(NormalizedTrade trade);

    /**
     * Called on every ConnectionState transition.
     * Always called before the first onSnapshot() in each connect cycle.
     */
    void onConnectionEvent(ConnectionEvent event);
}
