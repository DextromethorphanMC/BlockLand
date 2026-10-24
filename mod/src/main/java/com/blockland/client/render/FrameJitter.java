package com.blockland.client.render;

import org.joml.Matrix4f;

/**
 * This frame's sub-pixel projection offset for temporal anti-aliasing and upscaling (rendering spec §4). The graphics
 * module sets it at the start of each frame; every projection that draws into the frame's main target (the level, the
 * hand, the viewmodel) shifts by it, so all of them sample the same sub-pixel position. Zero when no temporal pass runs.
 * With it comes the frame's texture mip bias ({@link #mipBias}).
 */
public final class FrameJitter {
    private static float x, y, mipBias;

    private FrameJitter() {
    }

    /** Offset in normalised device coordinates (one pixel = 2 / width). */
    public static float x() {
        return x;
    }

    public static float y() {
        return y;
    }

    /**
     * Texture LOD bias for this frame's draws (≤ 0): a frame rendered below the output resolution and resolved temporally
     * gathers its detail over several jittered frames, so textures are sampled at the output's mip level,
     * log2(render width / output width). Zero when no temporal pass runs. Applied by {@link Textures} to its own textures.
     */
    public static float mipBias() {
        return mipBias;
    }

    /** Graphics module only (no temporal pass: zero offset and bias). */
    public static void set(float ndcX, float ndcY) {
        set(ndcX, ndcY, 0);
    }

    /** Graphics module only. */
    public static void set(float ndcX, float ndcY, float bias) {
        x = ndcX;
        y = ndcY;
        mipBias = bias;
    }

    /** Shifts a projection matrix by this frame's offset (in place) and returns it. */
    public static Matrix4f apply(Matrix4f projection) {
        if (x != 0 || y != 0) projection.translateLocal(x, y, 0);
        return projection;
    }
}
