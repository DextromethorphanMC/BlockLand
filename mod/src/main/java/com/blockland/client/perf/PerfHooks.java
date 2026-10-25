package com.blockland.client.perf;

import net.minecraft.util.profiling.ProfilerFiller;

/** What the perf module's mixins call (com.blockland.mixin.core): nothing else of the module is reached from vanilla. */
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

    static void holdTicks(boolean held) {
        ticksHeld = held;
    }
}
