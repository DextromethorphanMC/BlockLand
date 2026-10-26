package com.blockland.client.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Which block textures the wind moves, and how (rendering spec §18), for things that must move with a plant: blood on a
 * blade of grass, on a leaf. The graphics module publishes the block materials' {@code wind} after every atlas stitch;
 * the bits are those {@code bl_blockWind} returns in the shaders ({@code blockland:bl_lighting.glsl}: mode | upper half
 * &lt;&lt; 2 | water &lt;&lt; 3 | flex level &lt;&lt; 4), so a shader handed them moves a point exactly as the terrain
 * shader moves the plant's vertices there ({@code blockland:bl_wind.glsl}). Client thread only, except {@link #moves} and
 * {@link #version} (any thread: the grid is replaced whole).
 */
public final class PlantWind {
    /** Modes (the low two bits). */
    public static final int STILL = 0, BEND = 1, SWAY = 2, LEAVES = 3;

    private static Map<ResourceLocation, Integer> bits = Map.of();
    /** The block atlas in the shader's material map cells, true where the texture moves; bumped with every publish. */
    private static volatile Grid grid = new Grid(new boolean[0], 0, 0);
    private static volatile int version;

    private record Grid(boolean[] moving, int width, int height) {
    }

    private PlantWind() {
    }

    /**
     * The graphics module: texture id → wind bits of every moving block texture (the rest are still), and the atlas cells
     * the terrain shader reads them from ({@code bl_blockWind}: cell = uv × size), true where a moving texture lies.
     */
    public static void publish(Map<ResourceLocation, Integer> byTexture, boolean[] moving, int width, int height) {
        bits = Map.copyOf(byTexture);
        grid = new Grid(moving.clone(), width, height);
        version++;
    }

    /**
     * Whether the terrain shader may move a vertex with these block atlas uvs (the cell its {@code bl_blockWind} reads holds
     * a moving texture). Any thread.
     */
    public static boolean moves(float u, float v) {
        Grid g = grid;
        if (g.width == 0) return false;
        int x = Math.min(g.width - 1, Math.max(0, (int) (u * g.width))), y = Math.min(g.height - 1, Math.max(0, (int) (v * g.height)));
        return g.moving[y * g.width + x];
    }

    /** Changes with every publish (what was worked out from {@link #moves} before is stale then). */
    public static int version() {
        return version;
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
