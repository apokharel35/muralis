package com.muralis.model;

public enum ConnectionState {
    CONNECTING,     // Initial connection attempt in progress
    CONNECTED,      // WebSocket open, snapshot received, deltas flowing
    RECONNECTING,   // Connection lost; attempting to re-establish
    DISCONNECTED    // Explicitly stopped; no reconnection will be attempted
}
