package com.blockland.client.perf.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Biome colour blending ({@code ClientLevel.calculateBlockTint}) during a section compile, with each sampled position's
 * colour worked out once. Vanilla's {@code BlockTintCache} keeps the blended colour per block, but a block it has not seen
 * yet (every block after a map load, a chunk load or a full rebuild) costs (2r+1)² fuzzed biome lookups — 25 at the
 * default radius — and the neighbours ask for 20 of the same 25 samples again.
 * <p>
 * Exactness. A sample is {@code resolver.getColor(level.getBiome(s).value(), s.x, s.z)} for the vanilla resolvers
 * (grass, foliage, dry foliage, water: functions of the biome, the colour maps and x/z only), and the blend sums the
 * samples' channels as integers, so a remembered sample gives the same blend. Samples are remembered per thread for one
 * compile only, inside a window around the section (other positions are worked out each time), and forgotten as soon as
 * the client changes biome data it could have read: every chunk that loads, drops or gets new biomes, a view-distance
 * or view-centre change of the chunk cache, and every invalidation of the tint caches ({@link #biomesChanged}, which
 * runs after the data change and before vanilla invalidates its own cache). So a colour that vanilla's cache keeps
 * after an invalidation is never computed from a sample read before it; only a blend running at the very moment the
 * main thread swaps a chunk's data mixes old and new values — as vanilla's own blend does then.
 */
public final class BiomeTints {
    /** Returned by {@link #blend} when vanilla's code should run. */
    public static final int VANILLA = -1;
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static final ColorResolver[] RESOLVERS = {
            BiomeColors.GRASS_COLOR_RESOLVER, BiomeColors.FOLIAGE_COLOR_RESOLVER,
            BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER, BiomeColors.WATER_COLOR_RESOLVER};

    private BiomeTints() {
    }

    /** Biome data the client can read changed (chunk cache, tint cache invalidation): remembered samples are dropped. */
    public static void biomesChanged() {
        EPOCH.incrementAndGet();
    }

    /** {@code level.calculateBlockTint(pos, resolver)} from remembered samples, or {@link #VANILLA}. */
    public static int blend(ClientLevel level, BlockPos pos, ColorResolver resolver) {
        Memo memo = TerrainCompile.tints();
        if (memo == null) return VANILLA;
        int slot = slot(resolver);
        if (slot < 0) return VANILLA;
        int i = Minecraft.getInstance().options.biomeBlendRadius().get();
        if (i == 0) return VANILLA;   // one sample per block: nothing to share
        memo.validate(level, EPOCH.get());

        int j = (i * 2 + 1) * (i * 2 + 1);
        int k = 0;
        int l = 0;
        int i1 = 0;
        int y = pos.getY();
        for (int z = pos.getZ() - i; z <= pos.getZ() + i; z++) {
            for (int x = pos.getX() - i; x <= pos.getX() + i; x++) {
                int j1 = memo.sample(level, resolver, slot, x, y, z);
                k += (j1 & 0xFF0000) >> 16;
                l += (j1 & 0xFF00) >> 8;
                i1 += j1 & 0xFF;
            }
        }
        return (k / j & 0xFF) << 16 | (l / j & 0xFF) << 8 | i1 / j & 0xFF;
    }

    private static int slot(ColorResolver resolver) {
        for (int s = 0; s < RESOLVERS.length; s++) if (RESOLVERS[s] == resolver) return s;
        return -1;
    }

    /**
     * One thread's samples: x and z from 8 blocks before the section to 8 after (the largest blend radius is 7), y from
     * one below to one above, per resolver; an entry counts when its stamp is the current generation.
     */
    static final class Memo {
        private static final int WIDTH = 32;
        private static final int HEIGHT = 18;
        private static final int VOLUME = WIDTH * WIDTH * HEIGHT;

        private final int[][] values = new int[RESOLVERS.length][];
        private final int[][] stamps = new int[RESOLVERS.length][];
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private int generation;
        private int epoch;
        @Nullable
        private ClientLevel level;
        private int minX, minY, minZ;

        void begin(int originX, int originY, int originZ) {
            minX = originX - 8;
            minY = originY - 1;
            minZ = originZ - 8;
            level = null;   // the first blend validates against the level and the epoch
        }

        void end() {
            level = null;
        }

        void validate(ClientLevel level, int epoch) {
            if (level != this.level || epoch != this.epoch) {
                this.level = level;
                this.epoch = epoch;
                if (++generation == 0) {
                    for (int[] s : stamps) if (s != null) Arrays.fill(s, 0);
                    generation = 1;
                }
            }
        }

        int sample(ClientLevel level, ColorResolver resolver, int slot, int x, int y, int z) {
            int dx = x - minX, dy = y - minY, dz = z - minZ;
            if ((dx | dy | dz) < 0 || dx >= WIDTH || dz >= WIDTH || dy >= HEIGHT) return colour(level, resolver, x, y, z);
            int i = (dy * WIDTH + dz) * WIDTH + dx;
            int[] stamp = stamps[slot];
            if (stamp == null) {
                stamp = stamps[slot] = new int[VOLUME];
                values[slot] = new int[VOLUME];
            }
            if (stamp[i] == generation) return values[slot][i];
            int value = colour(level, resolver, x, y, z);
            values[slot][i] = value;
            stamp[i] = generation;
            return value;
        }

        private int colour(ClientLevel level, ColorResolver resolver, int x, int y, int z) {
            BlockPos.MutableBlockPos p = cursor.set(x, y, z);
            return resolver.getColor(level.getBiome(p).value(), p.getX(), p.getZ());
        }
    }
}
