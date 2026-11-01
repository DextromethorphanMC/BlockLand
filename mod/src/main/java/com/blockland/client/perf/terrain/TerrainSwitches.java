package com.blockland.client.perf.terrain;

import com.blockland.client.perf.Optimization;

import javax.annotation.Nullable;

/**
 * The terrain entries as the meshing code sees them: {@link Optimization#on()}, unless {@link MeshCheck} forces every
 * terrain entry off or on (one of them perhaps the other way) for the compiles it runs itself on the render thread (the
 * live worker threads keep the player's choice meanwhile).
 */
final class TerrainSwitches {
    private static volatile Thread forcedThread;
    private static volatile boolean forcedOn;
    @Nullable
    private static volatile Optimization forcedExcept;

    private TerrainSwitches() {
    }

    static boolean on(Optimization o) {
        return forcedThread == Thread.currentThread() ? forcedOn != (o == forcedExcept) : o.on();
    }

    /** Forces the entries for the calling thread, {@code except} the other way ({@code on == null}: back to the switches). */
    static void force(@Nullable Boolean on, @Nullable Optimization except) {
        if (on == null) {
            forcedThread = null;
        } else {
            forcedOn = on;
            forcedExcept = except;
            forcedThread = Thread.currentThread();
        }
    }
}
