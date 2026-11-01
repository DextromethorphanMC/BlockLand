package com.blockland.client.perf.terrain;

import com.blockland.mixin.terrain.WeightedVariantsAccess;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.block.model.SingleVariant;
import net.minecraft.client.resources.model.WeightedVariants;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code ENCLOSED_MODELS}: block models that draw nothing once all six of their faces are culled, whatever variant the
 * block's seed picks — every part of every variant has only quads with a face to be culled by, none from
 * {@code getQuads(null)} (the quads {@code ModelBlockRenderer} draws whatever the neighbours are). Only models whose
 * variants can be read whole count: a single variant or weighted variants of plain baked parts
 * ({@code SimpleModelWrapper}, quads fixed when baked). Anything else — multipart, connected textures and other
 * wrappers, Forge's own models — is not known here and keeps the full path.
 * <p>
 * Remembered per state id together with the model it was read from, so a resource reload (new model objects) is
 * noticed by the next look-up. Any thread: entries are immutable, a race only reads a model twice.
 */
final class CullableModels {
    private record Known(BlockStateModel model, boolean cullable) {
    }

    private static volatile Known[] known = new Known[0];

    private CullableModels() {
    }

    static boolean cullable(BlockState state, BlockStateModel model) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        Known[] table = known;
        if (id >= 0 && id < table.length) {
            Known k = table[id];
            if (k != null && k.model == model) return k.cullable;
        }
        boolean cullable = read(model, new ArrayList<>(), RandomSource.create(0));
        if (id >= 0) {
            if (id >= table.length) {
                synchronized (CullableModels.class) {
                    table = known;
                    if (id >= table.length) known = table = Arrays.copyOf(table, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
                }
            }
            table[id] = new Known(model, cullable);
        }
        return cullable;
    }

    /** False for a model it cannot read whole, or one with a quad drawn whatever the neighbours. */
    private static boolean read(BlockStateModel model, List<BlockModelPart> parts, RandomSource random) {
        if (model.getClass() == WeightedVariants.class) {
            for (Weighted<BlockStateModel> entry : ((WeightedVariantsAccess) model).blockland$list().unwrap()) {
                if (!read(entry.value(), parts, random)) return false;
            }
            return true;
        }
        if (model.getClass() != SingleVariant.class) return false;
        // a single variant adds its one part, whatever the random source
        parts.clear();
        model.collectParts(random, parts);
        for (BlockModelPart part : parts) {
            if (part.getClass() != SimpleModelWrapper.class || !part.getQuads(null).isEmpty()) return false;
        }
        return true;
    }
}
