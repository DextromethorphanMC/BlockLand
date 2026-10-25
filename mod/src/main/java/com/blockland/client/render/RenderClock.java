package com.blockland.client.render;

/**
 * The clock everything on screen animates by (wind, ripples, sway, flicker, fades, the post chain's frame time). It is
 * {@link System#nanoTime()} — except while the benchmark's frame comparison holds it ({@link #pin}): then every frame
 * sees a fixed step, so two renders of a still scene are the same frame for frame. Durations that measure work (time
 * budgets, statistics) keep reading the real clock.
 */
public final class RenderClock {
    private static volatile boolean pinned;
    private static volatile long pinnedNanos;

    private RenderClock() {
    }

    public static long nanos() {
        return pinned ? pinnedNanos : System.nanoTime();
    }

    /** {@link #nanos()} in milliseconds (where code animated by {@code Util.getMillis()}). */
    public static long millis() {
        return nanos() / 1_000_000L;
    }

    /** Benchmark only: from now on the clock reads {@code nanos} until it is stepped or released. */
    public static void pin(long nanos) {
        pinnedNanos = nanos;
        pinned = true;
    }

    /** Benchmark only: the next frame's time while pinned. */
    public static void step(long nanos) {
        if (pinned) pinnedNanos += nanos;
    }

    public static void release() {
        pinned = false;
    }

    public static boolean pinned() {
        return pinned;
    }
}
