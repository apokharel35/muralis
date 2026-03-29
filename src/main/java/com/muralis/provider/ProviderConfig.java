package com.muralis.provider;

public record ProviderConfig(
    String symbol,          // Canonical symbol e.g. "BTCUSDT"
    String wsUrlOverride,   // null = use provider default URL
    String restUrlOverride, // null = use provider default URL
    int    connectTimeoutMs // default: 10_000
) {
    public static ProviderConfig defaultFor(String symbol) {
        return new ProviderConfig(symbol, null, null, 10_000);
    }
}
