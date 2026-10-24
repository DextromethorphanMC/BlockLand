package com.blockland.client.graphics;

import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Last frame's world as screen-space reflections read it (rendering spec §17): its colour and linear depth, taken after
 * the level (before the hand and the viewmodel), so this frame's water and puddles can march their reflected rays
 * through it.
 *
 * @param scene RGBA16F: rgb the world's colour as drawn (display), a its linear depth in blocks (0 = sky)
 * @param steps the most steps a ray takes (the quality)
 */
public record ReflectionInfo(GpuTexture scene, int steps) {
}
