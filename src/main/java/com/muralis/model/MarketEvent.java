package com.muralis.model;

public sealed interface MarketEvent
    permits OrderBookSnapshot, OrderBookDelta, NormalizedTrade, ConnectionEvent {
}
