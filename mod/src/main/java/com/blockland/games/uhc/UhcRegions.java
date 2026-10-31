package com.blockland.games.uhc;

import com.blockland.BlockLand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where each UHC match plays: every match gets a square of untouched terrain of its own in the mod's UHC dimension (a
 * generated overworld, {@code data/blockland/dimension/uhc.json}), on a grid far apart so matches never meet and never
 * play on terrain an earlier match changed. The next free square is counted in the world's save, so a restart does
 * not hand one out twice. A square that would be mostly sea (judged from the climate, before anything is generated) is
 * skipped for the next one.
 */
final class UhcRegions {
    static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, BlockLand.id("uhc"));
    /** Blocks between square centres: well beyond the largest border plus everyone's view distance. */
    private static final int SPACING = 8192;
    private static final int COLUMNS = 64;
    /** Squares looked at before taking one whatever its sea. */
    private static final int TRIES = 24;
    /** Climate samples per side; more than this share of sea or river skips the square. */
    private static final int SAMPLES = 9;
    private static final float MAX_WET = 0.3f;

    private UhcRegions() {
    }

    /** The centre of a fresh square (y unused) for a border of half-width {@code radius}. Server thread. */
    static BlockPos allocate(ServerLevel level, int radius) {
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("blockland").resolve("uhc").resolve("next_region.txt");
        int next = read(file);
        BlockPos centre = null;
        for (int tries = 0; centre == null; tries++) {
            int n = next++;
            BlockPos candidate = new BlockPos((n % COLUMNS + 1) * SPACING, 0, (n / COLUMNS + 1) * SPACING);
            if (tries + 1 >= TRIES || dry(level, candidate, radius)) centre = candidate;
        }
        write(file, next);
        return centre;
    }

    /** Little enough sea and river over the square, by the biomes the generator will place (no chunk is made). */
    private static boolean dry(ServerLevel level, BlockPos centre, int radius) {
        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int y = QuartPos.fromBlock(level.getSeaLevel());
        int wet = 0;
        for (int i = 0; i < SAMPLES; i++) {
            for (int j = 0; j < SAMPLES; j++) {
                int x = centre.getX() - radius + radius * 2 * i / (SAMPLES - 1);
                int z = centre.getZ() - radius + radius * 2 * j / (SAMPLES - 1);
                Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), y, QuartPos.fromBlock(z), sampler);
                if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) wet++;
            }
        }
        return wet <= MAX_WET * SAMPLES * SAMPLES;
    }

    private static int read(Path file) {
        try {
            return Files.exists(file) ? Math.max(0, Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).trim())) : 0;
        } catch (IOException | NumberFormatException e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not read {}, UHC regions start over", file, e);
            return 0;
        }
    }

    private static void write(Path file, int next) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Integer.toString(next), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not save {}", file, e);
        }
    }
}
