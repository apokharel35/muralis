package com.muralis.provider;

public enum ProviderType {
    BINANCE_SPOT,       // Binance Spot WebSocket (Phase 1)
    BINANCE_FUTURES,    // Binance USD-M Futures WebSocket (future)
    CME_RITHMIC,        // CME via Rithmic R|Protocol (future)
    CME_CQG,            // CME via CQG WebAPI (future)
    COINBASE_ADVANCED   // Coinbase Advanced Trade WebSocket (future)
}
