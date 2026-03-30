package com.muralis.engine;

/**
 * Mutable rendering configuration.
 * Owned and written by the UI thread; read by the engine thread during buildSnapshot().
 * All fields are volatile — single writer, single reader, no compound operations,
 * so volatile provides sufficient visibility without synchronisation overhead.
 */
public class RenderConfig {

    private volatile long bubbleDecayMs = 5_000L;
    private volatile int  visibleLevels = 20;

    long bubbleDecayMs()                  { return bubbleDecayMs; }
    void setBubbleDecayMs(long ms)        { this.bubbleDecayMs = ms; }

    int  visibleLevels()                  { return visibleLevels; }
    void setVisibleLevels(int levels)     { this.visibleLevels = levels; }
}
