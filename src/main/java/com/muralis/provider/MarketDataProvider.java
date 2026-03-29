package com.muralis.provider;

import com.muralis.model.ConnectionState;
import com.muralis.model.InstrumentSpec;

public interface MarketDataProvider {

    /**
     * Returns the unique name of this provider.
     * Used for logging and status display only.
     * Example: "Binance Spot", "Rithmic CME"
     */
    String getName();

    /**
     * Returns the ProviderType enum value for this provider.
     */
    ProviderType getType();

    /**
     * Initiates the connection and bootstrap sequence.
     * Blocks until the WebSocket connection is established and
     * the first snapshot bootstrap has begun.
     * Does NOT block until CONNECTED — the caller receives
     * ConnectionState updates via the registered listeners.
     *
     * Precondition:  At least one MarketDataListener is registered.
     * Precondition:  connect() has not already been called.
     * Postcondition: A ConnectionEvent(CONNECTING) has been published.
     *
     * @param config Connection parameters (symbol, URL overrides, etc.)
     * @throws IllegalStateException if connect() is called twice
     */
    void connect(ProviderConfig config);

    /**
     * Disconnects cleanly. Publishes ConnectionEvent(DISCONNECTED).
     * Safe to call from any thread.
     * Idempotent — calling disconnect() twice is safe and has no effect
     * after the first call.
     *
     * Postcondition: No further events are published after this returns.
     */
    void disconnect();

    /**
     * Registers a listener to receive MarketEvent notifications.
     * Must be called before connect().
     * Multiple listeners may be registered.
     *
     * @param listener Must not be null.
     */
    void addListener(MarketDataListener listener);

    /**
     * Returns the InstrumentSpec for the currently connected symbol.
     * Returns null if connect() has not been called yet.
     */
    InstrumentSpec getInstrumentSpec();

    /**
     * Returns the current ConnectionState.
     * Thread-safe — may be called from any thread.
     */
    ConnectionState getConnectionState();
}
