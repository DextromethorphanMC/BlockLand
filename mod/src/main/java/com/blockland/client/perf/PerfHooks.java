package com.blockland.client.perf;

import net.minecraft.util.profiling.ProfilerFiller;

/** What the perf module's mixins call (com.blockland.mixin.core, the frame flip in com.blockland.mixin.gl): nothing else of the module is reached from vanilla. */
public final class PerfHooks {
    private static volatile boolean ticksHeld;

    private PerfHooks() {
    }

    /** The frame's profiler: vanilla's, plus the vanilla sections as spans while timings are recorded. */
    public static ProfilerFiller profiler(ProfilerFiller vanilla) {
        return FrameTimings.recording() ? ProfilerFiller.combine(vanilla, VanillaSections.INSTANCE) : vanilla;
    }

    /** The client's tick is skipped (benchmark frame comparison only: the world on screen must not move). */
    public static boolean ticksHeld() {
        return ticksHeld;
    }

    /** The two waits inside vanilla's frame flip (com.blockland.mixin.gl), timed for the freeze log. */
    public enum DisplayStep {SWAP, EVENTS}

    /** Start or end of a {@link DisplayStep} (render thread). */
    public static void displayStep(DisplayStep step, boolean start) {
        Hitches.displayStep(step, start);
    }

    /** Vanilla's frame rate limit for this frame, lowered while the window is in the background ({@link BackgroundLimit}). */
    public static int frameLimit(int vanilla) {
        return BackgroundLimit.apply(vanilla);
    }

    static void holdTicks(boolean held) {
        ticksHeld = held;
    }
}
