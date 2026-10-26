package com.blockland.client.graphics.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Cursor3D;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The world round one far unit as Minecraft's block renderer asks for it (rendering spec §23), on a mesh worker, in the
 * unit's own block grid (a unit of scale k: cubes of 2^k blocks, a position = a cube): blocks and fluids from the unit and
 * its neighbours of the same scale ({@link Snapshot}s taken on the render thread), biome tints blended over
 * vanilla's radius with vanilla's own biome zoom, sky and block light from the saved light (sky light the save lacks: 15
 * above the column's highest block, 0 below). Nothing of the client level is read here except constants.
 */
final class ColumnGetter implements BlockAndTintGetter, BiomeManager.NoiseBiomeSource {
    /** One column's sections and light as the getter reads them (immutable once handed over). */
    record Snapshot(int cx, int cz, int minSection, LevelChunkSection[] sections, byte[][] sky, byte[][] block, int topY) {
        @Nullable
        LevelChunkSection section(int y) {
            int i = (y >> 4) - minSection;
            return i < 0 || i >= sections.length ? null : sections[i];
        }
    }

    private final Snapshot[] around = new Snapshot[9];
    private final int centreX, centreZ, minY, height;
    private final BiomeManager biomes;
    private final int blendRadius;
    private final float[] shades = new float[12];
    private final Map<ColorResolver, Map<Long, Integer>> tints = new HashMap<>();
    @Nullable
    private final Holder<Biome> fallbackBiome;

    /**
     * {@code around}: the 3 × 3 units centred on the one meshed (index (dx + 1) + 3 (dz + 1)), null where unknown;
     * {@code minY}, {@code height}: the unit's grid (its sections' range, in cubes).
     */
    ColumnGetter(Snapshot[] around, ClientLevel level, int minY, int height, @Nullable Holder<Biome> fallbackBiome) {
        System.arraycopy(around, 0, this.around, 0, 9);
        this.centreX = around[4].cx;
        this.centreZ = around[4].cz;
        this.minY = minY;
        this.height = height;
        this.biomes = level.getBiomeManager().withDifferentSource(this);
        this.blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
        this.fallbackBiome = fallbackBiome;
        for (Direction d : Direction.values()) {
            shades[d.ordinal()] = level.getShade(d, true);
            shades[6 + d.ordinal()] = level.getShade(d, false);
        }
    }

    @Nullable
    private Snapshot column(int x, int z) {
        int dx = (x >> 4) - centreX, dz = (z >> 4) - centreZ;
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1) return null;
        return around[dx + 1 + (dz + 1) * 3];
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        Snapshot c = column(pos.getX(), pos.getZ());
        if (c == null) return Blocks.AIR.defaultBlockState();
        LevelChunkSection s = c.section(pos.getY());
        return s == null ? Blocks.AIR.defaultBlockState() : s.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        Snapshot c = column(pos.getX(), pos.getZ());
        if (c == null) return Fluids.EMPTY.defaultFluidState();
        LevelChunkSection s = c.section(pos.getY());
        return s == null ? Fluids.EMPTY.defaultFluidState() : s.getFluidState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public float getShade(Direction side, boolean shade) {
        return shades[(shade ? 0 : 6) + side.ordinal()];
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("far chunks answer light through getBrightness");
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        Snapshot c = column(pos.getX(), pos.getZ());
        if (c == null) return layer == LightLayer.SKY ? 15 : 0;
        int i = (pos.getY() >> 4) - c.minSection;
        byte[][] arrays = layer == LightLayer.SKY ? c.sky : c.block;
        byte[] data = i >= 0 && i < arrays.length ? arrays[i] : null;
        if (data == null) {
            if (layer == LightLayer.BLOCK) return 0;
            return pos.getY() > c.topY ? 15 : 0;
        }
        int index = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
        return data[index >> 1] >> ((index & 1) << 2) & 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return Math.max(getBrightness(LightLayer.SKY, pos) - darkening, getBrightness(LightLayer.BLOCK, pos));
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return getBrightness(LightLayer.SKY, pos) >= 15;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        long key = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        Map<Long, Integer> cache = tints.computeIfAbsent(resolver, r -> new HashMap<>());
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        int colour = blend(pos, resolver);
        cache.put(key, colour);
        return colour;
    }

    /** Vanilla's {@code ClientLevel.calculateBlockTint}: the resolver's colour averaged over the blend radius. */
    private int blend(BlockPos pos, ColorResolver resolver) {
        int r = blendRadius;
        if (r == 0) return resolver.getColor(biomes.getBiome(pos).value(), pos.getX(), pos.getZ());
        int count = (r * 2 + 1) * (r * 2 + 1), red = 0, green = 0, blue = 0;
        Cursor3D cursor = new Cursor3D(pos.getX() - r, pos.getY(), pos.getZ() - r, pos.getX() + r, pos.getY(), pos.getZ() + r);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (cursor.advance()) {
            at.set(cursor.nextX(), cursor.nextY(), cursor.nextZ());
            int c = resolver.getColor(biomes.getBiome(at).value(), at.getX(), at.getZ());
            red += c >> 16 & 255;
            green += c >> 8 & 255;
            blue += c & 255;
        }
        return (red / count & 255) << 16 | (green / count & 255) << 8 | blue / count & 255;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
        Snapshot c = column(qx << 2, qz << 2);
        int y = Mth.clamp(qy << 2, minY, minY + height - 1);
        LevelChunkSection s = c == null ? null : c.section(y);
        if (s == null) {
            // a column out of reach (beyond the neighbours): the nearest known biome stands in
            c = around[4];
            s = c.section(y);
            if (s == null) {
                for (LevelChunkSection any : c.sections) {
                    if (any != null) {
                        s = any;
                        break;
                    }
                }
            }
            if (s == null) return fallbackBiome;
            return s.getNoiseBiome(Math.floorMod(qx, 4), Math.floorMod(qy, 4), Math.floorMod(qz, 4));
        }
        return s.getNoiseBiome(qx & 3, (y >> 2) & 3, qz & 3);
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    static int sectionOf(int y) {
        return SectionPos.blockToSectionCoord(y);
    }
}
