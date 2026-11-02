package com.blockland.client.render;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts every change to what the level renderer's sections hold: a section's mesh set (a compile's result, a reset to
 * uncompiled) or its place set (the view area moved with the camera, was resized or rebuilt). {@code mixin/terrain}
 * reports them; readers that walk the sections and keep what they found — the sun shadow map's caster gather
 * (rendering spec §6) — compare {@link #current} with the count they walked at: equal, and no section can hold
 * anything else than it did.
 */
public final class TerrainVersion {
    private static final AtomicLong VERSION = new AtomicLong();

    private TerrainVersion() {
    }

    /** A section's mesh or place was set. */
    public static void bump() {
        VERSION.incrementAndGet();
    }

    public static long current() {
        return VERSION.get();
    }
}
