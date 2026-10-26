package com.blockland.client.ctm;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A block's model wrapped by the connected textures (rendering spec §25). {@link #getModelData} — which the section
 * compile (and the far terrain's column mesher) calls with the region and the position — looks at the 26 blocks round
 * it once and keeps, per rule and face, which in-plane neighbours connect; {@link #collectParts} hands the wrapped
 * model's parts out wrapped in turn, and their quads come out with the chosen tile instead of the rule's source
 * texture. Without that data (item rendering, block-breaking overlays, shadow casters) the model is the wrapped one.
 * <p>
 * Every path below the parts (vanilla's tesselation, the section-meshing optimizations) only sees ordinary quads, so
 * the output is the same with every optimization on or off.
 */
final class ConnectedModel implements BlockStateModel {
    static final ModelProperty<State> STATE = new ModelProperty<>();

    /** What {@link #getModelData} found: per rule, eight bits per face (bits {@code face * 8 ..}); the block's seed. */
    record State(long[] masks, long seed) {
    }

    /** A quad no rule touches. */
    private static final QuadInfo NONE = new QuadInfo(-1, 0, null, null);

    private final BlockStateModel wrapped;
    private final List<CtmRule> rules;
    /** Per source quad (keyed by its vertex array: arrays hash by identity). */
    private final ConcurrentHashMap<int[], QuadInfo> quads = new ConcurrentHashMap<>();

    ConnectedModel(BlockStateModel wrapped, List<CtmRule> rules) {
        this.wrapped = wrapped;
        this.rules = rules;
    }

    // ---- neighbours ---------------------------------------------------------------------------------

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos, @NotNull BlockState state,
                                           @NotNull ModelData modelData) {
        ModelData data = wrapped.getModelData(level, pos, state, modelData);
        BlockState[] around = new BlockState[27];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    around[index(dx, dy, dz)] = level.getBlockState(at.setWithOffset(pos, dx, dy, dz));
                }
            }
        }
        long[] masks = new long[rules.size()];
        boolean[] joins = new boolean[27];
        for (int r = 0; r < masks.length; r++) {
            CtmRule rule = rules.get(r);
            if (rule.method == CtmRule.Method.RANDOM) continue;
            for (int i = 0; i < 27; i++) joins[i] = i != 13 && rule.connects(state, around[i]);
            long mask = 0;
            for (Direction face : Direction.values()) {
                if ((rule.faces & 1 << face.ordinal()) == 0) continue;
                Vec3i right = CtmTiles.right(face), up = CtmTiles.up(face), n = face.getUnitVec3i();
                int bits = 0;
                for (int k = 0; k < 8; k++) {
                    int x = CtmTiles.DIRS[k][0], y = CtmTiles.DIRS[k][1];
                    int ox = x * right.getX() + y * up.getX(), oy = x * right.getY() + y * up.getY(), oz = x * right.getZ() + y * up.getZ();
                    boolean join = joins[index(ox, oy, oz)];
                    // its own face here is hidden behind another connected block: the surface turns a corner, keep the frame
                    if (join && rule.exposedOnly && joins[index(ox + n.getX(), oy + n.getY(), oz + n.getZ())]) join = false;
                    if (join) bits |= 1 << k;
                }
                mask |= (long) bits << face.ordinal() * 8;
            }
            masks[r] = mask;
        }
        return data.derive().with(STATE, new State(masks, Mth.getSeed(pos))).build();
    }

    private static int index(int dx, int dy, int dz) {
        return (dx + 1) * 9 + (dy + 1) * 3 + dz + 1;
    }

    // ---- parts --------------------------------------------------------------------------------------

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> dest, ModelData data, @Nullable ChunkSectionLayer renderType) {
        int start = dest.size();
        wrapped.collectParts(random, dest, data, renderType);
        State state = data.get(STATE);
        if (state == null) return;
        for (int i = start; i < dest.size(); i++) dest.set(i, new Part(dest.get(i), state));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> dest) {
        wrapped.collectParts(random, dest);
    }

    @SuppressWarnings("deprecation")
    @Override
    public TextureAtlasSprite particleIcon() {
        return wrapped.particleIcon();
    }

    @Override
    public TextureAtlasSprite particleIcon(@NotNull ModelData data) {
        return wrapped.particleIcon(data);
    }

    @Override
    public Collection<ChunkSectionLayer> getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        return wrapped.getRenderTypes(state, rand, data);
    }

    /** A part of the wrapped model whose quads come out with their tiles for one block. */
    private final class Part implements BlockModelPart {
        private final BlockModelPart part;
        private final State state;

        Part(BlockModelPart part, State state) {
            this.part = part;
            this.state = state;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable Direction direction) {
            List<BakedQuad> source = part.getQuads(direction);
            List<BakedQuad> out = null;
            for (int i = 0; i < source.size(); i++) {
                BakedQuad quad = source.get(i);
                BakedQuad tiled = tiled(quad, state);
                if (tiled != quad && out == null) {
                    out = new ArrayList<>(source.size());
                    for (int j = 0; j < i; j++) out.add(source.get(j));
                }
                if (out != null) out.add(tiled);
            }
            return out == null ? source : out;
        }

        @Override
        public boolean useAmbientOcclusion() {
            return part.useAmbientOcclusion();
        }

        @Override
        public TextureAtlasSprite particleIcon() {
            return part.particleIcon();
        }

        @Override
        public ChunkSectionLayer layer() {
            return part.layer();
        }

        @Override
        public ChunkSectionLayer layerFast() {
            return part.layerFast();
        }
    }

    // ---- quads --------------------------------------------------------------------------------------

    /** The quad with its tile for this block, or the quad itself. */
    private BakedQuad tiled(BakedQuad quad, State state) {
        QuadInfo info = quads.computeIfAbsent(quad.vertices(), v -> info(quad));
        if (info == NONE) return quad;
        CtmRule rule = rules.get(info.rule);
        TextureAtlasSprite[] sprites = rule.sprites();
        if (sprites == null) return quad;
        int face = quad.direction().ordinal();
        int faceMask = (int) (state.masks[info.rule] >>> face * 8) & 0xFF;
        long seed = state.seed * 0x9E3779B97F4A7C15L + face * 31L + info.rule;
        seed ^= seed >>> 29;
        int tile = rule.tile(CtmTiles.toTexture(faceMask, info.perm), seed);
        BakedQuad[] made = info.tiles;
        BakedQuad out = made[tile];
        if (out == null) {
            // a benign race: two threads make equal quads
            out = remap(quad, sprites[tile]);
            made[tile] = out;
        }
        return out;
    }

    private QuadInfo info(BakedQuad quad) {
        Direction face = quad.direction();
        for (int r = 0; r < rules.size(); r++) {
            CtmRule rule = rules.get(r);
            if (!rule.appliesTo(face, quad.sprite().contents().name())) continue;
            int[] perm = frame(quad);
            if (perm == null) return NONE;
            return new QuadInfo(r, face.ordinal(), perm, new BakedQuad[rule.tiles.length]);
        }
        return NONE;
    }

    /**
     * Where +u and +v run on a quad in its face's frame, as the permutation of directions; null when the quad's uvs do
     * not run along the face's axes (a rotated element).
     */
    @Nullable
    static int[] frame(BakedQuad quad) {
        int[] v = quad.vertices();
        Vec3i right = CtmTiles.right(quad.direction()), up = CtmTiles.up(quad.direction());
        float[] a = new float[3], b = new float[3], us = new float[3], vs = new float[3];
        for (int i = 0; i < 3; i++) {
            int o = i * 8;
            float x = Float.intBitsToFloat(v[o]), y = Float.intBitsToFloat(v[o + 1]), z = Float.intBitsToFloat(v[o + 2]);
            a[i] = x * right.getX() + y * right.getY() + z * right.getZ();
            b[i] = x * up.getX() + y * up.getY() + z * up.getZ();
            us[i] = Float.intBitsToFloat(v[o + 4]);
            vs[i] = Float.intBitsToFloat(v[o + 5]);
        }
        float a1 = a[1] - a[0], b1 = b[1] - b[0], a2 = a[2] - a[0], b2 = b[2] - b[0];
        float det = a1 * b2 - a2 * b1;
        if (Math.abs(det) < 1e-7f) return null;
        int[] uDir = axis(((us[1] - us[0]) * b2 - (us[2] - us[0]) * b1) / det, (a1 * (us[2] - us[0]) - a2 * (us[1] - us[0])) / det);
        int[] vDir = axis(((vs[1] - vs[0]) * b2 - (vs[2] - vs[0]) * b1) / det, (a1 * (vs[2] - vs[0]) - a2 * (vs[1] - vs[0])) / det);
        if (uDir == null || vDir == null || (uDir[0] != 0) == (vDir[0] != 0)) return null;
        return CtmTiles.permutation(uDir[0], uDir[1], vDir[0], vDir[1]);
    }

    /** A gradient (along right, along up) as a unit step on one axis, or null when it runs diagonally. */
    @Nullable
    private static int[] axis(float alongRight, float alongUp) {
        float ar = Math.abs(alongRight), au = Math.abs(alongUp);
        if (ar > 1e-7f && au <= ar * 1e-3f) return new int[]{alongRight > 0 ? 1 : -1, 0};
        if (au > 1e-7f && ar <= au * 1e-3f) return new int[]{0, alongUp > 0 ? 1 : -1};
        return null;
    }

    /** The quad with its uvs moved from its own sprite to {@code tile}, at the same place within it. */
    static BakedQuad remap(BakedQuad quad, TextureAtlasSprite tile) {
        TextureAtlasSprite from = quad.sprite();
        int[] v = quad.vertices().clone();
        float du = from.getU1() - from.getU0(), dv = from.getV1() - from.getV0();
        for (int o = 0; o + 8 <= v.length; o += 8) {
            float u = (Float.intBitsToFloat(v[o + 4]) - from.getU0()) / du;
            float w = (Float.intBitsToFloat(v[o + 5]) - from.getV0()) / dv;
            v[o + 4] = Float.floatToRawIntBits(tile.getU0() + u * (tile.getU1() - tile.getU0()));
            v[o + 5] = Float.floatToRawIntBits(tile.getV0() + w * (tile.getV1() - tile.getV0()));
        }
        return new BakedQuad(v, quad.tintIndex(), quad.direction(), tile, quad.shade(), quad.lightEmission(), quad.ambientOcclusion());
    }

    /** What a source quad needs: its rule, face, frame and the tiled copies made so far. */
    private record QuadInfo(int rule, int face, int[] perm, BakedQuad[] tiles) {
    }
}
