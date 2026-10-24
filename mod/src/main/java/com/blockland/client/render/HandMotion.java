package com.blockland.client.render;

import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;

/**
 * The first-person weapon's and arms' own screen motion (rendering spec §4). The camera's movement does not move the
 * viewmodel on screen, but its sway, recoil, bob and every reload animation do; the graphics module hands out a velocity
 * texture for each frame it runs (RG16F at the frame's render size, uv per frame, current − previous position, zero where
 * nothing moved), and the viewmodel draws its parts into it right after their colour, depth-tested against their own
 * depth ({@link DrawList#motion}). The post chain blurs the weapon by it and the temporal upscalers (and DLSS) reproject
 * it with it. Null between frames and in frames the pipeline does not run. Render thread only.
 */
public final class HandMotion {
    @Nullable
    private static GpuTextureView target;

    private HandMotion() {
    }

    /** This frame's velocity texture while the hand phase draws, or null. */
    @Nullable
    public static GpuTextureView target() {
        return target != null && !target.isClosed() ? target : null;
    }

    /** Graphics module only: from the end of the level to the composite. */
    public static void setTarget(@Nullable GpuTextureView value) {
        target = value;
    }
}
