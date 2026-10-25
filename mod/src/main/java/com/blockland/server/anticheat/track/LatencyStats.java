package com.blockland.server.anticheat.track;

/**
 * A player's measured round trip (anti-cheat spec §5): from our pings to their pongs, which their client answers on its
 * main thread in packet order — so it is the delay that matters for what the client knew, frame time included. Smoothed
 * mean, smoothed deviation (jitter) and the largest of the last samples. Before the first pong, vanilla's keep-alive
 * latency stands in.
 */
public final class LatencyStats {
    private static final int RECENT = 10;
    private static final double SMOOTHING = 0.25;

    private double rtt = -1;
    private double jitter;
    private final double[] recent = new double[RECENT];
    private int recentCount;
    private int recentHead;

    public void add(double ms) {
        if (!(ms >= 0) || ms > 60_000) return;
        if (rtt < 0) {
            rtt = ms;
            jitter = ms * 0.1;
        } else {
            jitter += (Math.abs(ms - rtt) - jitter) * SMOOTHING;
            rtt += (ms - rtt) * SMOOTHING;
        }
        recent[recentHead] = ms;
        recentHead = (recentHead + 1) % RECENT;
        recentCount = Math.min(RECENT, recentCount + 1);
    }

    public boolean measured() {
        return rtt >= 0;
    }

    /** Smoothed round trip in milliseconds, {@code fallback} (vanilla's keep-alive latency) before any sample. */
    public double rtt(double fallback) {
        return rtt >= 0 ? rtt : Math.max(0, fallback);
    }

    public double jitter() {
        return jitter;
    }

    /** The largest of the last samples ({@code fallback} before any). */
    public double recentMax(double fallback) {
        if (recentCount == 0) return Math.max(0, fallback);
        double max = 0;
        for (int i = 0; i < recentCount; i++) max = Math.max(max, recent[i]);
        return max;
    }

    /**
     * Ticks a state change may take to reach the client (or the client's own change to reach us), per the preset's ping
     * settings: the round trip plus a multiple of the jitter, capped, plus a margin.
     */
    public int windowTicks(double fallbackMs, double maxCompensationMs, double jitterMultiplier, int extraTicks) {
        double ms = Math.min(Math.max(0, maxCompensationMs), rtt(fallbackMs) + Math.max(0, jitterMultiplier) * jitter);
        return (int) Math.ceil(ms / 50.0) + Math.max(0, extraTicks);
    }

    public void reset() {
        rtt = -1;
        jitter = 0;
        recentCount = 0;
        recentHead = 0;
    }
}
