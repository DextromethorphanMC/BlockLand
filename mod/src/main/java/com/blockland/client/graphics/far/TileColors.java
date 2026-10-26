package com.blockland.client.graphics.far;

import com.blockland.farterrain.FarTile;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a far terrain cell looks like (rendering spec §22), resolved on the render thread per tile palette: for every
 * block state × biome of the tile its top face and its sides — the face's sprite in the block atlas ({@link #spriteCode},
 * drawn block by block like the real terrain), the face's tint in that biome, and the sprite's alpha-weighted average
 * times the tint for faces without a usable sprite — the light it gives off, and per biome the water's colour. Cached
 * across tiles; cleared on a resource reload.
 */
final class TileColors {
    /** One tile's looks, indexed {@code state * biomes + biome} (states and biomes are the tile's palette indices). */
    record Palette(int biomes, int[] top, int[] side, int[] topTint, int[] sideTint, int[] topSprite, int[] sideSprite,
                   int[] emission, boolean[] water, int[] waterColor) {
        /** Average colour × tint (0xRRGGBB). */
        int top(int state, int biome) {
            return top[state * biomes + biome];
        }

        int side(int state, int biome) {
            return side[state * biomes + biome];
        }

        /** The tint alone (white for untinted faces). */
        int topTint(int state, int biome) {
            return topTint[state * biomes + biome];
        }

        int sideTint(int state, int biome) {
            return sideTint[state * biomes + biome];
        }
    }

    /** Set in a sprite code when the face is drawn from the atlas. */
    static final int TEXTURED = 1 << 28;

    /**
     * A sprite's place in the block atlas for the far terrain's vertex: x / 16 (bits 0–11), y / 16 (12–23), log2 of its
     * size / 16 (24–27), {@link #TEXTURED}; 0 when it cannot be drawn that way (not square, not a power of two from 16 on,
     * not on the 16-texel grid).
     */
    static int spriteCode(TextureAtlasSprite sprite) {
        int x = sprite.getX(), y = sprite.getY(), w = sprite.contents().width(), h = sprite.contents().height();
        if (w != h || w < 16 || Integer.bitCount(w) != 1 || x % 16 != 0 || y % 16 != 0 || x / 16 >= 4096 || y / 16 >= 4096) return 0;
        int size = Integer.numberOfTrailingZeros(w / 16);
        if (size > 15) return 0;
        return x / 16 | (y / 16) << 12 | size << 24 | TEXTURED;
    }

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private final Long2ObjectOpenHashMap<int[]> looks = new Long2ObjectOpenHashMap<>();
    private final Map<TextureAtlasSprite, Integer> averages = new IdentityHashMap<>();
    private final Map<String, Holder<Biome>> biomes = new HashMap<>();
    private final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>();
    private final BiomeTint tint = new BiomeTint();
    @Nullable
    private ClientLevel level;

    /** The looks of a tile's palette; the level's registries resolve its biomes. */
    Palette palette(FarTile tile, ClientLevel level) {
        if (level != this.level) {
            this.level = level;
            biomes.clear();
            biomeIds.clear();
            looks.clear();
        }
        int states = Math.max(1, tile.stateCount()), biomeCount = Math.max(1, tile.biomeCount());
        int[] top = new int[states * biomeCount], side = new int[states * biomeCount], emission = new int[states];
        int[] topTint = new int[states * biomeCount], sideTint = new int[states * biomeCount];
        int[] topSprite = new int[states], sideSprite = new int[states];
        boolean[] water = new boolean[states];
        int[] waterColor = new int[biomeCount];
        // a position in the middle of the tile for the tints that vary over the ground (swamps)
        BlockPos centre = new BlockPos(tile.originX() + FarTile.span(tile.level) / 2, 64, tile.originZ() + FarTile.span(tile.level) / 2);
        Holder<Biome>[] holders = resolveBiomes(tile, level);
        for (int s = 0; s < tile.stateCount(); s++) {
            BlockState state = Block.stateById(tile.state(s));
            if (state == null) state = Blocks.STONE.defaultBlockState();
            emission[s] = state.getLightEmission();
            FluidState fluid = state.getFluidState();
            water[s] = fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER);
            for (int b = 0; b < biomeCount; b++) {
                int[] look = look(state, tile.state(s), holders[b], centre);
                top[s * biomeCount + b] = look[0];
                side[s * biomeCount + b] = look[1];
                topTint[s * biomeCount + b] = look[2];
                sideTint[s * biomeCount + b] = look[3];
                topSprite[s] = look[4];
                sideSprite[s] = look[5];
            }
        }
        BlockState waterState = Blocks.WATER.defaultBlockState();
        for (int b = 0; b < biomeCount; b++) waterColor[b] = look(waterState, Block.getId(waterState), holders[b], centre)[0];
        return new Palette(biomeCount, top, side, topTint, sideTint, topSprite, sideSprite, emission, water, waterColor);
    }

    @SuppressWarnings("unchecked")
    private Holder<Biome>[] resolveBiomes(FarTile tile, ClientLevel level) {
        int count = Math.max(1, tile.biomeCount());
        Holder<Biome>[] holders = new Holder[count];
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        for (int b = 0; b < count; b++) {
            String id = b < tile.biomeCount() ? tile.biome(b) : "minecraft:plains";
            holders[b] = biomes.computeIfAbsent(id, key -> {
                ResourceLocation location = ResourceLocation.tryParse(key);
                Holder<Biome> holder = location == null ? null : registry.get(ResourceKey.create(Registries.BIOME, location)).orElse(null);
                return holder != null ? holder : registry.getOrThrow(Biomes.PLAINS);
            });
        }
        return holders;
    }

    /** {top, side} average colours × tint, {top, side} tints, {top, side} sprite codes of a state in a biome. */
    private int[] look(BlockState state, int stateId, Holder<Biome> biome, BlockPos at) {
        int biomeId = biomeIds.computeIfAbsent(biome, b -> biomeIds.size());
        long key = (long) stateId << 20 | biomeId;
        int[] look = looks.get(key);
        if (look != null) return look;
        tint.biome = biome;
        BlockColors colors = Minecraft.getInstance().getBlockColors();
        look = new int[6];
        try {
            BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            List<BlockModelPart> parts = model.collectParts(RandomSource.create(42));
            BakedQuad topQuad = firstQuad(parts, Direction.UP);
            BakedQuad sideQuad = null;
            for (Direction d : SIDES) {
                sideQuad = firstQuad(parts, d);
                if (sideQuad != null) break;
            }
            if (topQuad == null) topQuad = firstQuad(parts, null);
            if (sideQuad == null) sideQuad = topQuad;
            TextureAtlasSprite particle = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            look[2] = tint(state, topQuad, colors, at);
            look[3] = tint(state, sideQuad, colors, at);
            look[0] = times(average(topQuad != null ? topQuad.sprite() : particle), look[2]);
            look[1] = times(average(sideQuad != null ? sideQuad.sprite() : particle), look[3]);
            // fluids have no model quads: their colour only (the water is drawn by its own shading)
            look[4] = topQuad != null && state.getFluidState().isEmpty() ? spriteCode(topQuad.sprite()) : 0;
            look[5] = sideQuad != null && state.getFluidState().isEmpty() ? spriteCode(sideQuad.sprite()) : 0;
        } catch (RuntimeException e) {
            look[0] = look[1] = 0x808080;
            look[2] = look[3] = 0xFFFFFF;
        }
        looks.put(key, look);
        return look;
    }

    @Nullable
    private static BakedQuad firstQuad(List<BlockModelPart> parts, @Nullable Direction side) {
        for (BlockModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(side);
            if (!quads.isEmpty()) return quads.get(0);
        }
        return null;
    }

    /** A face's tint in the biome (0xRRGGBB, white when untinted); without a model quad (fluids) the block's own tint. */
    private int tint(BlockState state, @Nullable BakedQuad quad, BlockColors colors, BlockPos at) {
        int tintIndex = quad != null ? quad.isTinted() ? quad.tintIndex() : -1 : 0;
        if (tintIndex < 0) return 0xFFFFFF;
        int tintColour = colors.getColor(state, tint, at, tintIndex);
        return tintColour == -1 ? 0xFFFFFF : tintColour & 0xFFFFFF;
    }

    private static int times(int rgb, int tint) {
        int r = (rgb >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int g = (rgb >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
        return r << 16 | g << 8 | b;
    }

    /** Alpha-weighted average of a sprite's first frame, 0xRRGGBB. */
    private int average(TextureAtlasSprite sprite) {
        Integer cached = averages.get(sprite);
        if (cached != null) return cached;
        int rgb = 0x808080;
        try {
            NativeImage image = sprite.contents().getOriginalImage();
            int w = sprite.contents().width(), h = sprite.contents().height();
            long r = 0, g = 0, b = 0, weight = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = image.getPixel(x, y);
                    int a = ARGB.alpha(argb);
                    r += (long) ARGB.red(argb) * a;
                    g += (long) ARGB.green(argb) * a;
                    b += (long) ARGB.blue(argb) * a;
                    weight += a;
                }
            }
            if (weight > 0) rgb = (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);
        } catch (RuntimeException e) {
            // a sprite without readable pixels: neutral grey
        }
        averages.put(sprite, rgb);
        return rgb;
    }

    void clear() {
        looks.clear();
        averages.clear();
        biomes.clear();
        biomeIds.clear();
        level = null;
    }

    /** A block getter that only knows its biome: block colour providers ask it for their tint and nothing else matters. */
    private static final class BiomeTint implements BlockAndTintGetter {
        Holder<Biome> biome;

        @Override
        public float getShade(Direction side, boolean shade) {
            return 1;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            throw new UnsupportedOperationException("far terrain tints have no light");
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(biome.value(), pos.getX(), pos.getZ());
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
