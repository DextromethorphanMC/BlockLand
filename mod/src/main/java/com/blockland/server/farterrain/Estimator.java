package com.blockland.server.farterrain;

import com.blockland.farterrain.FarTile;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terrain where no chunk exists yet (rendering spec §22): the world generator's own column for the shape — for noise
 * generators the height of the topmost solid block as the game will generate it, the sea over anything below sea level —
 * the biome at the surface from the generator's biome source, and {@link SurfaceTable} for the blocks seen there. Flat
 * generators: their layers. Called from worker threads (the generator's column functions are made for that).
 */
abstract class Estimator {
    /**
     * Cells this wide and wider take the guided scan of the final density, cells from {@link #FAST_CELL} on the fast shape
     * alone; single-block cells (right beyond the loaded chunks, and usually real by then) the generator's exact column.
     * Measured with {@code /bl far probe} on the overworld preset (400 columns): exact 2.0 ms; guided 0.28 ms, −0.7 ± 2.0
     * blocks; fast 0.07 ms, −0.1 ± 4.4 blocks.
     */
    static final int GUIDED_CELL = 2, FAST_CELL = 64;
    /** Where the jagged-less density counts as ground, and the blocks added to its height (calibrated with the probe). */
    static final double FAST_THRESHOLD = -0.4;
    static final int FAST_OFFSET = 4;

    /** One estimated column: top face height ({@link FarTile#EMPTY} = nothing), top and under state ids, water depth. */
    static final class Sample {
        int height, top, under, water;
        String biome = "minecraft:plains";
    }

    /** One column for a cell {@code cell} blocks wide (coarse cells may take the fast shape and a canopy's average). */
    abstract void sample(int x, int z, int cell, Sample out);

    /** Accuracy and cost of the estimate against the generator's exact column (the {@code /bl far probe} command). */
    List<String> probe(int columns) {
        return List.of("no noise generator here");
    }

    /** The estimator of a level's noise generator, or null (for the probe). */
    @Nullable
    static Estimator noiseOf(ServerLevel level) {
        Estimator e = of(level, SurfaceTable.parse(null));
        return e instanceof Noise ? e : null;
    }

    /** The estimator of a level, or null when its generator gives nothing to estimate (void, debug, unknown kinds). */
    @Nullable
    static Estimator of(ServerLevel level, SurfaceTable table) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        if (generator instanceof NoiseBasedChunkGenerator noise) return new Noise(level, noise, random, table);
        if (generator instanceof FlatLevelSource flat) {
            Flat estimator = new Flat(level, flat, random);
            return estimator.empty ? null : estimator;
        }
        return null;
    }

    /** Noise generators: shape and sea from the generator, the look from the biome table. */
    private static final class Noise extends Estimator {
        private final ServerLevel level;
        private final NoiseBasedChunkGenerator generator;
        private final RandomState random;
        private final BiomeSource biomes;
        private final Climate.Sampler sampler;
        private final SurfaceTable table;
        private final int seaLevel, minY;
        private final Map<String, Resolved> resolved = new ConcurrentHashMap<>();
        /** The terrain's shape without its jagged peaks and caves: a 2D-cheap density that falls with height. */
        private final DensityFunction shape, finalDensity;
        private final int shapeMin, shapeMax;

        Noise(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random, SurfaceTable table) {
            this.level = level;
            this.generator = generator;
            this.random = random;
            this.biomes = generator.getBiomeSource();
            this.sampler = random.sampler();
            this.table = table;
            this.seaLevel = generator.getSeaLevel();
            this.minY = level.getMinY();
            this.shape = random.router().initialDensityWithoutJaggedness();
            this.finalDensity = random.router().finalDensity();
            NoiseSettings noise = generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(level);
            this.shapeMin = noise.minY();
            this.shapeMax = noise.minY() + noise.height() - 1;
        }

        /**
         * The fast column height (cells of {@link #FAST_CELL} blocks and wider, hundreds of blocks away): the highest y where
         * the jagged-less initial density exceeds {@link #FAST_THRESHOLD}, found by bisection (it falls with height over the
         * terrain), plus
         * {@link #FAST_OFFSET}. A dozen 2D-cheap evaluations instead of the generator's whole interpolated column.
         */
        int fastHeight(int x, int z) {
            int lo = shapeMin, hi = shapeMax;
            if (shape.compute(new DensityFunction.SinglePointContext(x, lo, z)) <= FAST_THRESHOLD) return minY;
            // no early answer at the top: near the build limit this density rises again, bisection steps over that
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (shape.compute(new DensityFunction.SinglePointContext(x, mid, z)) > FAST_THRESHOLD) lo = mid;
                else hi = mid;
            }
            return lo + 1 + FAST_OFFSET;
        }

        @Override
        List<String> probe(int columns) {
            Random random = new Random(columns * 31L);
            double[] thresholds = {-0.4, -0.3, -0.2, -0.15, -0.1, -0.05, 0.0};
            int t = thresholds.length;
            long exactNanos = 0, fastNanos = 0, scanNanos = 0, guidedNanos = 0;
            double guidedSum = 0, guidedSq = 0, fastSum = 0, fastSq = 0;
            double[] sum = new double[t + 1], sq = new double[t + 1];
            for (int n = 0; n < columns; n++) {
                int x = random.nextInt(40000) - 20000, z = random.nextInt(40000) - 20000;
                long t0 = System.nanoTime();
                int exact = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, this.random);
                long t1 = System.nanoTime();
                fastHeight(x, z);
                long t2 = System.nanoTime();
                int scanned = scan(finalDensity, x, z);
                long t3 = System.nanoTime();
                int guided = guidedHeight(x, z);
                guidedNanos += System.nanoTime() - t3;
                guidedSum += guided - exact;
                guidedSq += (double) (guided - exact) * (guided - exact);
                int fast = fastHeight(x, z);
                fastSum += fast - exact;
                fastSq += (double) (fast - exact) * (fast - exact);
                exactNanos += t1 - t0;
                fastNanos += t2 - t1;
                scanNanos += t3 - t2;
                for (int k = 0; k < t; k++) {
                    double d = heightAt(x, z, thresholds[k]) - exact;
                    sum[k] += d;
                    sq[k] += d * d;
                }
                double d = scanned - exact;
                sum[t] += d;
                sq[t] += d * d;
            }
            List<String> lines = new ArrayList<>();
            lines.add(String.format("exact %.1f us, fast %.1f us, final-density scan %.1f us, guided %.1f us per column (%d columns)",
                    exactNanos / 1e3 / columns, fastNanos / 1e3 / columns, scanNanos / 1e3 / columns, guidedNanos / 1e3 / columns, columns));
            double gm = guidedSum / columns, fm = fastSum / columns;
            lines.add(String.format("in use: guided %+.2f sd %.2f, fast %+.2f sd %.2f blocks", gm, Math.sqrt(Math.max(0, guidedSq / columns - gm * gm)),
                    fm, Math.sqrt(Math.max(0, fastSq / columns - fm * fm))));
            for (int k = 0; k <= t; k++) {
                double mean = sum[k] / columns, sd = Math.sqrt(Math.max(0, sq[k] / columns - mean * mean));
                lines.add(String.format("%s: mean %+.2f, sd %.2f blocks", k < t ? String.format("threshold %+.2f", thresholds[k]) : "scan",
                        mean, sd));
            }
            return lines;
        }

        /**
         * The top of the final density (uninterpolated: the terrain as generated to a block or two), searched from the fast
         * shape's guess: up in steps of 8 while solid, down in steps of 4 while air, then block by block.
         */
        int guidedHeight(int x, int z) {
            int y = Math.max(shapeMin, Math.min(shapeMax, fastHeight(x, z) + 12));
            while (y < shapeMax && finalDensity.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0) y = Math.min(shapeMax, y + 8);
            while (y > shapeMin && finalDensity.compute(new DensityFunction.SinglePointContext(x, y, z)) <= 0) y -= 4;
            if (y <= shapeMin) return minY;
            int top = Math.min(shapeMax, y + 4);
            while (top > y && finalDensity.compute(new DensityFunction.SinglePointContext(x, top, z)) <= 0) top--;
            return top + 1;
        }

        /** The top of the uninterpolated final density, scanned down in steps of 4 then refined (the probe's comparison). */
        private int scan(DensityFunction density, int x, int z) {
            int y = shapeMax;
            while (y > shapeMin && density.compute(new DensityFunction.SinglePointContext(x, y, z)) <= 0) y -= 4;
            int top = Math.min(shapeMax, y + 4);
            while (top > y && density.compute(new DensityFunction.SinglePointContext(x, top, z)) <= 0) top--;
            return top + 1;
        }

        private int heightAt(int x, int z, double threshold) {
            int lo = shapeMin, hi = shapeMax;
            if (shape.compute(new DensityFunction.SinglePointContext(x, lo, z)) <= threshold) return minY;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (shape.compute(new DensityFunction.SinglePointContext(x, mid, z)) > threshold) lo = mid;
                else hi = mid;
            }
            return lo + 1;
        }

        @Override
        void sample(int x, int z, int cell, Sample out) {
            int h = cell >= FAST_CELL ? fastHeight(x, z)
                    : cell >= GUIDED_CELL ? guidedHeight(x, z)
                    : generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
            if (h <= minY) {
                out.height = FarTile.EMPTY;
                return;
            }
            boolean wet = h < seaLevel;
            int surface = wet ? seaLevel : h;
            Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface - 1), QuartPos.fromBlock(z), sampler);
            String id = ChunkColumns.biomeId(biome);
            Resolved look = resolved.computeIfAbsent(id, k -> new Resolved(table.rule(k, tag -> hasTag(biome, tag))));
            out.biome = id;
            if (wet) {
                out.height = seaLevel;
                if (look.waterTop != 0) {
                    out.top = look.waterTop;
                    out.under = look.waterTop;
                    out.water = 0;
                } else {
                    out.top = ChunkColumns.WATER;
                    out.under = look.floor;
                    out.water = Math.min(255, seaLevel - h);
                }
                return;
            }
            out.height = h;
            out.top = look.surface;
            out.under = look.under;
            out.water = 0;
            if (look.stoneLine != Integer.MAX_VALUE && h >= look.stoneLine) {
                out.top = look.stone;
                out.under = look.stone;
            }
            if (look.snowLine != Integer.MAX_VALUE && h >= look.snowLine) out.top = look.snow;
            // a cell wider than a tree shows the canopy's average: a forest is one green roof, open land none
            boolean canopy = cell >= 8 ? look.canopyCover >= 0.5f : cover(x, z) < look.canopyCover;
            if (look.canopyHeight > 0 && canopy) {
                out.height = h + look.canopyHeight;
                out.top = look.leaves;
                out.under = look.leaves;
            }
        }

        private static boolean hasTag(Holder<Biome> biome, String tag) {
            ResourceLocation id = ResourceLocation.tryParse(tag);
            return id != null && biome.is(TagKey.create(Registries.BIOME, id));
        }
    }

    /** A deterministic 0..1 per column: whether a canopy covers it (trees of about 5 blocks across, not single columns). */
    static float cover(int x, int z) {
        int cx = Math.floorDiv(x, 5), cz = Math.floorDiv(z, 5);
        long h = (long) cx * 0x9E3779B97F4A7C15L ^ (long) cz * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ h >>> 31) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    /** A rule's block ids as state ids (unknown ids fall back to the default rule's, then to grass, dirt, sand). */
    private static final class Resolved {
        final int surface, under, floor, waterTop, snow, stone, leaves;
        final int snowLine, stoneLine, canopyHeight;
        final float canopyCover;

        Resolved(SurfaceTable.Rule rule) {
            surface = state(rule.surface(), Blocks.GRASS_BLOCK);
            under = state(rule.under(), Blocks.DIRT);
            floor = state(rule.floor(), Blocks.SAND);
            waterTop = rule.waterTop() == null ? 0 : state(rule.waterTop(), Blocks.ICE);
            snow = state(rule.snow(), Blocks.SNOW_BLOCK);
            stone = state(rule.stone(), Blocks.STONE);
            snowLine = rule.snowLine() == null ? Integer.MAX_VALUE : rule.snowLine();
            stoneLine = rule.stoneLine() == null ? Integer.MAX_VALUE : rule.stoneLine();
            SurfaceTable.Canopy canopy = rule.canopy();
            canopyHeight = canopy == null ? 0 : canopy.height();
            canopyCover = canopy == null ? 0 : canopy.cover();
            leaves = canopy == null ? 0 : state(canopy.leaves(), Blocks.OAK_LEAVES);
        }

        private static int state(@Nullable String id, Block fallback) {
            ResourceLocation key = id == null ? null : ResourceLocation.tryParse(id);
            Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            return Block.getId((block == null || block == Blocks.AIR ? fallback : block).defaultBlockState());
        }
    }

    /** Flat generators: the same column everywhere, the biome from the biome source. */
    private static final class Flat extends Estimator {
        private final BiomeSource biomes;
        private final Climate.Sampler sampler;
        private final boolean empty;
        private final int height, top, under, water;

        Flat(ServerLevel level, FlatLevelSource flat, RandomState random) {
            biomes = flat.getBiomeSource();
            sampler = random.sampler();
            NoiseColumn column = flat.getBaseColumn(0, 0, level, random);
            int minY = level.getMinY();
            int y = level.getMaxY();
            while (y >= minY && column.getBlock(y).isAir()) y--;
            empty = y < minY;
            if (empty) {
                height = FarTile.EMPTY;
                top = under = water = 0;
                return;
            }
            BlockState topState = column.getBlock(y);
            if (!topState.getFluidState().isEmpty() && topState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                int floor = y;
                while (floor >= minY && !column.getBlock(floor).getFluidState().isEmpty()) floor--;
                top = ChunkColumns.WATER;
                under = floor >= minY ? Block.getId(column.getBlock(floor)) : 0;
                water = Math.max(1, Math.min(255, y - floor));
            } else {
                top = Block.getId(topState);
                BlockState below = y - 3 >= minY ? column.getBlock(y - 3) : topState;
                under = Block.getId(below.isAir() ? topState : below);
                water = 0;
            }
            height = y + 1;
        }

        @Override
        void sample(int x, int z, int cell, Sample out) {
            out.height = height;
            out.top = top;
            out.under = under;
            out.water = water;
            out.biome = ChunkColumns.biomeId(biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(height - 1), QuartPos.fromBlock(z), sampler));
        }
    }
}
