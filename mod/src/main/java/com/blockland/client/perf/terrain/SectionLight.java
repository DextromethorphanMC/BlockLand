package com.blockland.client.perf.terrain;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Light and shade of one section compile, for the section and its one-block border (18³ blocks): what
 * {@code ModelBlockRenderer.Cache} keeps for the last 100 positions only (two linked hash maps, so ambient occlusion
 * reads most positions several times through the light engine as the scan moves on), kept here for the whole compile in
 * flat arrays stamped with the compile's generation. Every ambient-occlusion and flat-lighting read of a section block
 * lies inside the border; anything else (another level, a model reaching further) goes to vanilla's cache.
 * <p>
 * Exactness. Each entry holds what vanilla computes on a miss for the same position:
 * {@code BrightnessGetter.DEFAULT.packedBrightness(region, pos)} for light (the state-dependent part of
 * {@code LevelRenderer.getLightColor} — emissive blocks, light emission — is still applied per call, by vanilla) and
 * {@code state.getShadeBrightness(region, pos)} for shade, where every caller passes the region's own state at
 * {@code pos} (the caches are keyed by position only in vanilla too). Shade only reads the compile's block snapshot, so
 * it is the same whenever it is read. Light comes from the level's light engine, which the render thread updates while
 * workers compile: in a world whose light is settled every read gives the same value, so keeping the first one changes
 * nothing; when light changes during a compile, vanilla's own result already depends on when each position happened
 * to be read (the 100-entry cache makes that arbitrary too), and the light update marks the section and its neighbours
 * dirty, so that mesh is replaced by one compiled after the change, which reads the settled values in both paths.
 */
public final class SectionLight implements LevelRenderer.BrightnessGetter {
    private static final int SIZE = 18;
    private static final int VOLUME = SIZE * SIZE * SIZE;

    private final int[] light = new int[VOLUME];
    private final int[] lightStamp = new int[VOLUME];
    private final float[] shade = new float[VOLUME];
    private final int[] shadeStamp = new int[VOLUME];
    private int generation;
    private int minX, minY, minZ;
    @Nullable
    private BlockAndTintGetter region;
    /** Vanilla's cached getter, for positions outside the window. */
    private LevelRenderer.BrightnessGetter fallback = LevelRenderer.BrightnessGetter.DEFAULT;

    void begin(int originX, int originY, int originZ, BlockAndTintGetter region) {
        if (++generation == 0) {
            Arrays.fill(lightStamp, 0);
            Arrays.fill(shadeStamp, 0);
            generation = 1;
        }
        this.minX = originX - 1;
        this.minY = originY - 1;
        this.minZ = originZ - 1;
        this.region = region;
    }

    void end() {
        region = null;
        fallback = LevelRenderer.BrightnessGetter.DEFAULT;
    }

    /**
     * The section's cache for {@code ModelBlockRenderer.Cache}, set when vanilla enables its caching at the start of a
     * compile that meshes the new way; null otherwise (vanilla's caches as they are).
     */
    @Nullable
    public static SectionLight attach(LevelRenderer.BrightnessGetter vanillaCached) {
        SectionLight s = TerrainCompile.light();
        if (s != null) s.fallback = vanillaCached;
        return s;
    }

    /** The slot of {@code pos} in this compile's window, or -1. */
    public int index(BlockAndTintGetter level, BlockPos pos) {
        if (level != region) return -1;
        int x = pos.getX() - minX, y = pos.getY() - minY, z = pos.getZ() - minZ;
        if ((x | y | z) < 0 || x >= SIZE || y >= SIZE || z >= SIZE) return -1;
        return (y * SIZE + z) * SIZE + x;
    }

    @Override
    public int packedBrightness(BlockAndTintGetter level, BlockPos pos) {
        int i = index(level, pos);
        if (i < 0) return fallback.packedBrightness(level, pos);
        if (lightStamp[i] == generation) return light[i];
        int value = LevelRenderer.BrightnessGetter.DEFAULT.packedBrightness(level, pos);
        light[i] = value;
        lightStamp[i] = generation;
        return value;
    }

    /** {@code state.getShadeBrightness(level, pos)} for the slot {@link #index} gave. */
    public float shade(int i, BlockState state, BlockAndTintGetter level, BlockPos pos) {
        if (shadeStamp[i] == generation) return shade[i];
        float value = state.getShadeBrightness(level, pos);
        shade[i] = value;
        shadeStamp[i] = generation;
        return value;
    }
}
