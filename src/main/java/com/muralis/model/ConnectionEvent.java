package com.muralis.model;

public record ConnectionEvent(
    String          symbol,        // Which instrument this event relates to
    ConnectionState state,         // New state
    long            receivedTs,    // System.currentTimeMillis() at event time
    String          reason         // Human-readable reason string. Never null.
) implements MarketEvent {
}
