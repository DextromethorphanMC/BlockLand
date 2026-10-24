package com.blockland.client.graphics;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

/**
 * This frame's sun shadow map (rendering spec §6), as the scene uniforms and the volumetric light read it.
 *
 * @param atlas        depth atlas with comparison sampling: 2×2 tiles, cascade c in tile ((c &amp; 1) / 2, (c &gt;&gt; 1) / 2)
 * @param cascades     per cascade: camera-relative world → cascade-local [0,1]³ (xy within its tile, z depth)
 * @param splits       per cascade: the distance from the camera (blocks) it covers up to
 * @param count        cascades in use (0..4)
 * @param atlasSize    side of the whole atlas in texels
 * @param filterRadius PCF radius in texels of the first cascade
 * @param normalOffset world-space normal offset (blocks) of the first cascade against acne
 * @param strength     0..1, how dark full shadow is relative to vanilla's skylight (weather and time of day applied)
 */
public record ShadowInfo(GpuTextureView atlas, Matrix4f[] cascades, float[] splits, int count, int atlasSize,
                         float filterRadius, float normalOffset, float strength) {
}
