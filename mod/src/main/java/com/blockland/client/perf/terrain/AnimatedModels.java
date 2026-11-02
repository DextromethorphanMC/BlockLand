package com.blockland.client.perf.terrain;

import com.blockland.mixin.terrain.WeightedVariantsAccess;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.block.model.SingleVariant;
import net.minecraft.client.resources.model.WeightedVariants;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which block models draw animated sprites (for {@code client/render/TerrainAnimation}): whether any quad of any variant
 * of a state's model has one. Known whole for a single variant or weighted variants of plain baked parts
 * ({@code SimpleModelWrapper}, quads fixed when baked), remembered per state id with the model it was read from (a
 * resource reload makes new models, noticed by the next look-up; any thread, entries immutable). Any other kind of model
 * — multipart, connected textures, Forge's own — is {@link #UNKNOWN} here, and the compile asks the very parts it draws
 * ({@link #animated(List)}) instead.
 */
final class AnimatedModels {
    static final int NO = 0, YES = 1, UNKNOWN = 2;
    private static final Direction[] FACES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private record Known(BlockStateModel model, int animated) {
    }

    private static volatile Known[] known = new Known[0];

    private AnimatedModels() {
    }

    /** {@link #YES}, {@link #NO}, or {@link #UNKNOWN} when the model cannot be read whole. */
    static int animated(BlockState state, BlockStateModel model) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        Known[] table = known;
        if (id >= 0 && id < table.length) {
            Known k = table[id];
            if (k != null && k.model == model) return k.animated;
        }
        int animated = read(model, new ArrayList<>(), RandomSource.create(0));
        if (id >= 0) {
            if (id >= table.length) {
                synchronized (AnimatedModels.class) {
                    table = known;
                    if (id >= table.length) known = table = Arrays.copyOf(table, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
                }
            }
            table[id] = new Known(model, animated);
        }
        return animated;
    }

    private static int read(BlockStateModel model, List<BlockModelPart> parts, RandomSource random) {
        if (model.getClass() == WeightedVariants.class) {
            boolean any = false;
            for (Weighted<BlockStateModel> entry : ((WeightedVariantsAccess) model).blockland$list().unwrap()) {
                int r = read(entry.value(), parts, random);
                if (r == UNKNOWN) return UNKNOWN;
                any |= r == YES;
            }
            return any ? YES : NO;
        }
        if (model.getClass() != SingleVariant.class) return UNKNOWN;
        // a single variant adds its one part, whatever the random source
        parts.clear();
        model.collectParts(random, parts);
        for (BlockModelPart part : parts) if (part.getClass() != SimpleModelWrapper.class) return UNKNOWN;
        return animated(parts) ? YES : NO;
    }

    /** Whether any quad of these parts, with or without a face to cull by, has an animated sprite. */
    static boolean animated(List<BlockModelPart> parts) {
        for (int p = 0, n = parts.size(); p < n; p++) {
            BlockModelPart part = parts.get(p);
            for (@Nullable Direction face : FACES) {
                List<BakedQuad> quads = part.getQuads(face);
                for (int q = 0, m = quads.size(); q < m; q++) if (quads.get(q).sprite().isAnimated()) return true;
            }
        }
        return false;
    }
}
