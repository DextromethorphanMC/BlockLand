package com.blockland.client.perf;

import net.minecraft.client.Minecraft;

/**
 * The frame rate while the game window is not the one in front (the player switched to another window): at most
 * {@link PerfSettings#backgroundFrameLimit}, through vanilla's own frame limiter. Vanilla only slows down a minimized
 * window or an idle player, so a window left behind another kept drawing as fast as the GPU allows; with two clients on
 * one machine the one behind took the GPU from the one in front, whose buffer swap then waited up to a second. Never
 * applies during a benchmark (its passes must measure the real frame cost, whatever window is in front).
 */
final class BackgroundLimit {
    /** Vanilla's 最大帧率 slider: this value means no limit. */
    static final int UNLIMITED = 260;
    static final int MIN = 10;

    private BackgroundLimit() {
    }

    static int apply(int vanilla) {
        int cap = Perf.settings().backgroundFrameLimit;
        if (cap >= UNLIMITED || cap >= vanilla || Benchmark.running() || Minecraft.getInstance().isWindowActive()) return vanilla;
        return cap;
    }
}
