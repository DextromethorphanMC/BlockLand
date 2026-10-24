package com.blockland.client.graphics.upscale;

import com.blockland.client.graphics.frame.FrameContext;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Turns the frame's linear HDR colour at the internal resolution into the output resolution (rendering spec §4): native
 * resolution (nothing to do), the built-in temporal upscaler (TAA at scale 1, TAAU below), or DLSS.
 */
public interface Upscaler extends AutoCloseable {
    /** Lower-case id for the status line: none, fxaa, taa, taau, dlss. */
    String id();

    /** Needs projection jitter and keeps a history between frames. */
    boolean temporal();

    /** This frame's result at the output resolution (may be the input itself at native resolution). */
    GpuTextureView resolve(Frame frame);

    /** Forgets the history (a camera cut, a resize, a settings change). */
    default void invalidate() {
    }

    @Override
    default void close() {
    }

    /**
     * What a resolve gets.
     *
     * @param color        linear HDR at the internal resolution; alpha 1 on hand / viewmodel pixels
     * @param worldDepth   the world's depth at the internal resolution (no hand), or null when it was not captured
     * @param reprojection current unjittered NDC → previous frame's clip space (camera-relative, camera motion included)
     * @param historyValid the previous frame can be reprojected into this one at all
     */
    record Frame(FrameContext context, GpuTextureView color, @Nullable GpuTextureView worldDepth, Matrix4f reprojection,
                 boolean historyValid) {
    }
}
