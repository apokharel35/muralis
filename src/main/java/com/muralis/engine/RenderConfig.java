package com.muralis.engine;

/**
 * Mutable rendering configuration.
 * Owned and written by the UI thread; read by the engine thread during buildSnapshot().
 * All fields are volatile — single writer, single reader, no compound operations,
 * so volatile provides sufficient visibility without synchronisation overhead.
 */
public class RenderConfig {

    private volatile long    bubbleDecayMs     = 5_000L;
    private volatile int     visibleLevels     = 20;
    private volatile boolean deltaTintEnabled  = true;
    private volatile double  deltaTintIntensity = 0.5;

    public long bubbleDecayMs()                  { return bubbleDecayMs; }
    public void setBubbleDecayMs(long ms)        { this.bubbleDecayMs = ms; }

    public int  visibleLevels()                  { return visibleLevels; }
    public void setVisibleLevels(int levels)     { this.visibleLevels = levels; }

    public boolean deltaTintEnabled()                    { return deltaTintEnabled; }
    public void setDeltaTintEnabled(boolean enabled)     { this.deltaTintEnabled = enabled; }

    public double deltaTintIntensity()                   { return deltaTintIntensity; }
    public void setDeltaTintIntensity(double intensity)  { this.deltaTintIntensity = intensity; }
}
