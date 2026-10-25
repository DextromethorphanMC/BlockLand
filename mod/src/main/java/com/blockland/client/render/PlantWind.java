package com.blockland.client.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Which block textures the wind moves, and how (rendering spec §18), for things that must move with a plant: blood on a
 * blade of grass, on a leaf. The graphics module publishes the block materials' {@code wind} after every atlas stitch;
 * the bits are those {@code bl_blockWind} returns in the shaders ({@code blockland:bl_lighting.glsl}: mode | upper half
 * &lt;&lt; 2 | water &lt;&lt; 3 | flex level &lt;&lt; 4), so a shader handed them moves a point exactly as the terrain
 * shader moves the plant's vertices there ({@code blockland:bl_wind.glsl}). Client thread only.
 */
public final class PlantWind {
    /** Modes (the low two bits). */
    public static final int STILL = 0, BEND = 1, SWAY = 2, LEAVES = 3;

    private static Map<ResourceLocation, Integer> bits = Map.of();

    private PlantWind() {
    }

    /** The graphics module: texture id → wind bits of every moving block texture (the rest are still). */
    public static void publish(Map<ResourceLocation, Integer> byTexture) {
        bits = Map.copyOf(byTexture);
    }

    /** The wind bits of a block texture, 0 = still. */
    public static int bits(ResourceLocation texture) {
        return bits.getOrDefault(texture, 0);
    }

    public static int bits(TextureAtlasSprite sprite) {
        return bits(sprite.contents().name());
    }

    public static int mode(int bits) {
        return bits & 3;
    }
}
