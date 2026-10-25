package com.blockland.client.perf.particles;

import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

import javax.annotation.Nullable;

/**
 * {@code PARTICLES}: a particle's light ({@code Particle.getLightColor} → {@code LevelRenderer.getLightColor}: the block
 * state, its emission, two light-engine lookups) looked up once per block for all the particles in it, within one
 * {@code ParticleEngine.render} call.
 * <p>
 * Exact: the value is a function of the level's block states and light data at the block. Within one particle render
 * neither changes — block changes arrive with packets and ticks, both run between frames; the light engine applies its
 * queued updates at the start of {@code LevelRenderer.renderLevel}, before the particle pass, never during it. The cache
 * lives for one {@code render} call only (a scope's second world render in the same frame starts it over), is kept for one
 * level instance (a lookup in another level goes straight through), and only holds blocks whose packed position is exact
 * ({@link BlockPos#asLong} keeps 26 / 12 / 26 bits: a particle far outside the world would alias another block). Off the
 * render thread, outside a particle render, or with the switch off at the render's start: vanilla's lookup.
 */
public final class ParticleLight {
    /** No entry (a light that happens to be this value is looked up each time — still exact). */
    private static final int NONE = Integer.MIN_VALUE;
    /** Half the range {@link BlockPos#asLong} packs exactly, horizontally and vertically. */
    private static final int HALF_XZ = 1 << BlockPos.PACKED_HORIZONTAL_LENGTH - 1, HALF_Y = 1 << BlockPos.PACKED_Y_LENGTH - 1;
    private static final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
    private static boolean active;
    @Nullable
    private static BlockAndTintGetter level;

    static {
        cache.defaultReturnValue(NONE);
    }

    private ParticleLight() {
    }

    /** {@code ParticleEngine.render} starts: a fresh cache when the switch is on (render thread). */
    public static void begin() {
        if (!RenderSystem.isOnRenderThread()) return;
        active = Optimization.PARTICLES.on();
        level = null;
        if (!cache.isEmpty()) cache.clear();
    }

    /** {@code ParticleEngine.render} returns: lookups go straight through again. */
    public static void end() {
        if (!RenderSystem.isOnRenderThread()) return;
        active = false;
        level = null;
    }

    /** In place of {@code LevelRenderer.getLightColor(level, pos)} in {@code Particle.getLightColor}. */
    public static int light(BlockAndTintGetter getter, BlockPos pos) {
        if (!active || !RenderSystem.isOnRenderThread()) return LevelRenderer.getLightColor(getter, pos);
        if (level == null) level = getter;
        else if (level != getter) return LevelRenderer.getLightColor(getter, pos);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (y < -HALF_Y || y >= HALF_Y || x < -HALF_XZ || x >= HALF_XZ || z < -HALF_XZ || z >= HALF_XZ) {
            return LevelRenderer.getLightColor(getter, pos);
        }
        long key = BlockPos.asLong(x, y, z);
        int light = cache.get(key);
        if (light == NONE) {
            light = LevelRenderer.getLightColor(getter, pos);
            if (light != NONE) cache.put(key, light);
        }
        return light;
    }
}
