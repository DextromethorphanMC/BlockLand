package com.blockland.client.graphics.raytrace;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What a block state is to the ray tracer (rendering spec §4): its average colour and how much of its cell it fills, packed
 * into one RGBA8 voxel. Colour is the average of the block's particle sprite (alpha-weighted, in display colours) times its
 * biome tint where it has one; the kind in alpha is 0 for nothing a ray stops at (air, water, glass, fluids), 255 for full
 * opaque cubes, and in between the chance that a ray crossing the cell hits something (leaves, slabs, stairs, fences,
 * plants). Cached per state, cleared on a resource reload.
 */
final class BlockVoxels {
    static final int KIND_FULL = 255, KIND_LEAVES = 170, KIND_PARTIAL = 120, KIND_PLANT = 40;

    private final Map<BlockState, Integer> cache = new IdentityHashMap<>();
    private final Map<BlockState, Boolean> tints = new IdentityHashMap<>();

    /** The packed voxel (RGBA bytes as an int, little-endian: 0xAABBGGRR) of a state; tinted states need {@link #tinted}. */
    int voxel(BlockState state) {
        Integer cached = cache.get(state);
        if (cached != null) return cached;
        int voxel = compute(state);
        cache.put(state, voxel);
        return voxel;
    }

    /** The state takes a biome tint (grass, leaves, vines…): its colour depends on where it is. */
    boolean tinted(BlockState state) {
        Boolean tinted = tints.get(state);
        if (tinted == null) {
            tinted = voxel(state) != 0 && Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0) != -1;
            tints.put(state, tinted);
        }
        return tinted;
    }

    /** {@link #voxel} with the tint at this position applied. */
    int voxel(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        int base = voxel(state);
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (tint == -1) return base;
        int r = (base & 0xFF) * ARGB.red(tint) / 255;
        int g = ((base >> 8) & 0xFF) * ARGB.green(tint) / 255;
        int b = ((base >> 16) & 0xFF) * ARGB.blue(tint) / 255;
        return (base & 0xFF000000) | (b << 16) | (g << 8) | r;
    }

    private static int compute(BlockState state) {
        if (state.isAir() || state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER)) return 0;
        int kind;
        if (state.isSolidRender()) kind = KIND_FULL;
        else if (state.is(BlockTags.LEAVES)) kind = KIND_LEAVES;
        else if (!state.getFluidState().isEmpty()) kind = KIND_FULL;  // lava
        else if (!state.canOcclude() && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            // glass panes and the like still collide; flowers, grass, torches do not
            kind = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() ? 0 : KIND_PLANT;
        } else if (state.propagatesSkylightDown()) kind = 0;           // glass, ice: light goes through
        else kind = KIND_PARTIAL;
        if (kind == 0) return 0;
        int color = averageColor(state, Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0) != -1);
        return (kind << 24) | (color & 0x00FFFFFF);
    }

    /**
     * The alpha-weighted average of the block's sprite (RGB in the low three bytes, R lowest): its particle sprite, or for
     * tinted blocks the top face's (a grass block's particle is plain dirt; its grey top is what the tint colours).
     */
    private static int averageColor(BlockState state, boolean tinted) {
        try {
            TextureAtlasSprite sprite = tinted ? topSprite(state) : null;
            if (sprite == null) sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
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
            if (weight == 0) return 0x808080;
            return (int) (r / weight) | (int) (g / weight) << 8 | (int) (b / weight) << 16;
        } catch (RuntimeException e) {
            // no model (a technical block): a neutral grey
            return 0x808080;
        }
    }

    private static TextureAtlasSprite topSprite(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        for (BlockModelPart part : model.collectParts(RandomSource.create(42))) {
            for (BakedQuad quad : part.getQuads(Direction.UP)) {
                if (quad.isTinted()) return quad.sprite();
            }
            for (BakedQuad quad : part.getQuads(null)) {
                if (quad.isTinted()) return quad.sprite();
            }
        }
        return null;
    }

    void clear() {
        cache.clear();
        tints.clear();
    }
}
