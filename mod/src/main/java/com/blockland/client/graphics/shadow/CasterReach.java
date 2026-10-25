package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.SimpleModelWrapper;
import net.minecraft.client.renderer.block.model.SingleVariant;
import net.minecraft.client.renderer.block.model.multipart.MultiPartModel;
import net.minecraft.client.resources.model.WeightedVariants;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * How far the terrain casters' geometry can stick out of its chunk section, measured, for culling sections by their box
 * ({@code SHADOW_CULLING}). A section's solid and cutout meshes hold its blocks' model quads, each block's moved by its
 * random offset, and lava surfaces, which stay inside their block (the shadow vertex shader moves nothing). Block
 * models may reach out of their block (elements from −16 to 32, rotated elements further), so nothing short of every
 * quad bounds them: every block state's model is read — every weighted variant, every multipart selector — and the
 * farthest any vertex lies outside [0, 1]³, plus the largest random offset, is the reach. Measured once per set of baked
 * models; anything it cannot read (a model or part of a kind it does not know) leaves the reach unknown and the culling
 * off. While resources reload, meshes of the old models and the new ones are on screen at once: no culling then.
 */
final class CasterReach {
    /** Over the measured reach: float rounding of the CPU test and the shader's transform (far below this). */
    private static final float MARGIN = 0.25f;
    private static final float UNKNOWN = -1;
    private static final Direction[] FACES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    @Nullable
    private static final Field WEIGHTED_LIST = field(WeightedVariants.class, "list");
    @Nullable
    private static final Field MULTIPART_SHARED = field(MultiPartModel.class, "shared");
    @Nullable
    private static final Field MULTIPART_SELECTORS = field(MultiPartModel.class.getName() + "$SharedBakedState", "selectors");
    @Nullable
    private static final Method MAX_HORIZONTAL = method("getMaxHorizontalOffset");
    @Nullable
    private static final Method MAX_VERTICAL = method("getMaxVerticalOffset");

    /** The model set measured: stone's model stands for it (every bake makes new model objects). */
    @Nullable
    private Object measured;
    private float reach = UNKNOWN;

    /** The padding of a section's box for this frame (blocks on every side), or negative for no culling this frame. */
    float padding() {
        Minecraft mc = Minecraft.getInstance();
        // a reload swaps the models before the level renderer drops the meshes built from the old ones
        if (mc.getOverlay() != null) return UNKNOWN;
        BlockModelShaper shaper = mc.getModelManager().getBlockModelShaper();
        BlockStateModel token = shaper.getBlockModel(Blocks.STONE.defaultBlockState());
        if (token != measured) {
            measured = token;
            long start = System.nanoTime();
            reach = measure(shaper);
            BlockLand.LOGGER.debug("[BlockLand] Shadow caster reach {} blocks (measured in {} ms)", reach,
                    (System.nanoTime() - start) / 1_000_000);
        }
        return reach < 0 ? UNKNOWN : reach + MARGIN;
    }

    private static float measure(BlockModelShaper shaper) {
        if (WEIGHTED_LIST == null || MULTIPART_SHARED == null || MULTIPART_SELECTORS == null || MAX_HORIZONTAL == null
                || MAX_VERTICAL == null) return UNKNOWN;
        try {
            Measure m = new Measure();
            Set<Block> offsetBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
            float offset = 0;
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                if (state.hasOffsetFunction() && offsetBlocks.add(state.getBlock())) {
                    // BlockBehaviour.Properties.offsetType: |x|, |z| <= max(0.25, |max horizontal|), y in [-|max vertical|, 0]
                    float horizontal = Math.max(0.25f, Math.abs((float) MAX_HORIZONTAL.invoke(state.getBlock())));
                    float vertical = Math.abs((float) MAX_VERTICAL.invoke(state.getBlock()));
                    offset = Math.max(offset, Math.max(horizontal, vertical));
                }
                if (!m.model(shaper.getBlockModel(state))) return UNKNOWN;
            }
            if (!Float.isFinite(m.most)) return UNKNOWN;
            // fluids (the liquid renderer) stay inside their block: no reach of their own
            return m.most + offset;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot measure the block models' reach ({}): shadow casters are culled by sphere", e.toString());
            return UNKNOWN;
        }
    }

    /** The farthest model vertex outside its block so far, over every model and part visited once. */
    private static final class Measure {
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<BlockModelPart> parts = new ArrayList<>();
        private final RandomSource random = RandomSource.create(0);
        float most;

        /** False for a model it does not know how to read whole. */
        @SuppressWarnings("unchecked")
        boolean model(BlockStateModel model) throws ReflectiveOperationException {
            if (model instanceof MultiPartModel multipart) {
                // every selector's model, whatever this state selects
                Object shared = MULTIPART_SHARED.get(multipart);
                if (!seen.add(shared)) return true;
                for (MultiPartModel.Selector<BlockStateModel> selector : (List<MultiPartModel.Selector<BlockStateModel>>) MULTIPART_SELECTORS.get(shared)) {
                    if (!model(selector.model())) return false;
                }
                return true;
            }
            if (!seen.add(model)) return true;
            if (model instanceof WeightedVariants weighted) {
                for (Weighted<BlockStateModel> entry : ((WeightedList<BlockStateModel>) WEIGHTED_LIST.get(weighted)).unwrap()) {
                    if (!model(entry.value())) return false;
                }
                return true;
            }
            if (model.getClass() != SingleVariant.class) return false;
            // a single variant adds its one part, whatever the random source
            parts.clear();
            model.collectParts(random, parts);
            for (BlockModelPart part : parts) {
                if (part.getClass() != SimpleModelWrapper.class) return false;
                if (!seen.add(part)) continue;
                for (Direction face : FACES) {
                    for (BakedQuad quad : part.getQuads(face)) quad(quad.vertices());
                }
            }
            return true;
        }

        private void quad(int[] vertices) {
            for (int v = 0; v + 2 < vertices.length; v += FaceBakery.VERTEX_INT_SIZE) {
                for (int axis = 0; axis < 3; axis++) {
                    float p = Float.intBitsToFloat(vertices[v + axis]);
                    // NaN would compare false: make it unknown
                    float out = Float.isNaN(p) ? Float.POSITIVE_INFINITY : Math.max(-p, p - 1);
                    if (out > most) most = out;
                }
            }
        }
    }

    @Nullable
    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] {}.{} unreadable ({}): shadow casters are culled by sphere", owner.getSimpleName(), name, e.toString());
            return null;
        }
    }

    @Nullable
    private static Field field(String owner, String name) {
        try {
            return field(Class.forName(owner), name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] {} unreadable ({}): shadow casters are culled by sphere", owner, e.toString());
            return null;
        }
    }

    @Nullable
    private static Method method(String name) {
        try {
            Method m = BlockBehaviour.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Block.{} unreadable ({}): shadow casters are culled by sphere", name, e.toString());
            return null;
        }
    }
}
