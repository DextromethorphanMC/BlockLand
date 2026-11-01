package com.blockland.client.perf.terrain;

import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.Optimization;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;

/**
 * One chunk section compile ({@code SectionCompiler.compile}) on the current thread: the terrain switches as they were
 * when it started and the scratch state of the optimized paths. Compiles run on vanilla's worker threads (and on the
 * render thread for sections the player changed), so the switches are read once here and kept for the whole section —
 * flipping one meanwhile never mixes the old and the new path inside a section (the flip rebuilds every section anyway).
 * <p>
 * What lives here exists per thread and is valid for one compile: the section's light and shade cache
 * ({@link SectionLight}), the biome colour samples ({@link BiomeTints}), the block the compile's walk is at, the face
 * culling already decided for the block being tesselated, and the vectors {@link QuadWriter} writes through.
 */
public final class TerrainCompile {
    private static final ThreadLocal<TerrainCompile> THREAD = ThreadLocal.withInitial(TerrainCompile::new);

    private boolean compiling;
    private boolean meshing;
    private boolean tints;
    /** {@code ENCLOSED_BLOCKS}: the full faces of the section and its one-block border ({@link #enclosed}). */
    private boolean enclosed;
    /** {@code ENCLOSED_MODELS}: enclosed blocks are left out before their model is read ({@link #enclosedModel}). */
    private boolean models;
    private final byte[] fullFaces = new byte[18 * 18 * 18];
    /** {@code HIDDEN_FLUIDS}: fluid blocks without a face to draw are left out ({@link #hiddenFluid}). */
    private boolean fluids;
    /** The block the compile's walk is at ({@link #blocks}), while {@link #models} or {@link #fluids} decides on it. */
    @Nullable
    private BlockPos current;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    /** {@code FACE_GROUPS}: the solid and cutout quads are grouped by facing at the end ({@link #finish}). */
    private boolean groups;
    int originX, originY, originZ;
    @Nullable
    private FrameTimings.Span span;
    @Nullable
    private SectionLight light;
    @Nullable
    private BiomeTints.Memo tintMemo;
    final Vector3f quadNormal = new Vector3f();
    final Vector3f quadPosition = new Vector3f();

    // the block whose faces were decided last (multipart models ask once per part and face)
    @Nullable
    private BlockAndTintGetter faceLevel;
    @Nullable
    private BlockState faceState;
    private long facePos;
    private boolean faceCull;
    private int faceKnown, faceRender;
    private final long[] faceNeighbour = new long[6];

    private TerrainCompile() {
    }

    /** A section compile starts on this thread (HEAD of {@code SectionCompiler.compile}). */
    public static void begin(SectionPos section, BlockAndTintGetter region) {
        TerrainCompile c = THREAD.get();
        c.compiling = true;
        c.meshing = TerrainSwitches.on(Optimization.SECTION_MESHING);
        c.tints = TerrainSwitches.on(Optimization.BIOME_TINTS);
        c.enclosed = TerrainSwitches.on(Optimization.ENCLOSED_BLOCKS);
        c.models = TerrainSwitches.on(Optimization.ENCLOSED_MODELS);
        c.fluids = TerrainSwitches.on(Optimization.HIDDEN_FLUIDS);
        c.groups = TerrainSwitches.on(Optimization.FACE_GROUPS);
        c.current = null;
        c.originX = section.minBlockX();
        c.originY = section.minBlockY();
        c.originZ = section.minBlockZ();
        c.faceLevel = null;
        c.faceState = null;
        if (c.meshing) {
            if (c.light == null) c.light = new SectionLight();
            c.light.begin(c.originX, c.originY, c.originZ, region);
        }
        if (c.tints) {
            if (c.tintMemo == null) c.tintMemo = new BiomeTints.Memo();
            c.tintMemo.begin(c.originX, c.originY, c.originZ);
        }
        if (c.enclosed || c.models) c.readFullFaces(region);
        // render-thread compiles only (sections the player changed); worker compiles are timed by MeshCheck
        c.span = FrameTimings.span("bl/terrain/compile");
    }

    /** The section and its border as full-face bits, index {@code (y * 18 + z) * 18 + x} from the origin − 1. */
    private void readFullFaces(BlockAndTintGetter region) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = -1; y <= 16; y++) {
            for (int z = -1; z <= 16; z++) {
                for (int x = -1; x <= 16; x++) {
                    fullFaces[i++] = (byte) FullFaces.of(region.getBlockState(pos.set(originX + x, originY + y, originZ + z)));
                }
            }
        }
    }

    /**
     * {@code ENCLOSED_BLOCKS}: whether tesselating this block would draw nothing — every neighbour's face against it is a
     * full block (so {@code Block.shouldRenderFace} culls each of its faces at its first test) and no part has quads
     * without a face to cull by ({@code getQuads(null)}, always drawn). Then {@code renderBatched} is left out: the same
     * mesh without the work of deciding six culled faces.
     */
    public static boolean enclosed(BlockPos pos, List<BlockModelPart> parts) {
        TerrainCompile c = THREAD.get();
        if (!c.compiling || !c.enclosed || !c.surrounded(pos)) return false;
        for (int p = 0, n = parts.size(); p < n; p++) {
            if (!parts.get(p).getQuads(null).isEmpty()) return false;
        }
        return true;
    }

    /** Whether every neighbour of a section block is full against it ({@link #readFullFaces} of this compile). */
    private boolean surrounded(BlockPos pos) {
        int x = pos.getX() - originX + 1, y = pos.getY() - originY + 1, z = pos.getZ() - originZ + 1;
        if (x < 1 || x > 16 || y < 1 || y > 16 || z < 1 || z > 16) return false;
        int i = (y * 18 + z) * 18 + x;
        byte[] f = fullFaces;
        // the neighbour below must be full on its up face, the one above on its down face, and so on
        return (f[i - 324] & 1 << 1) != 0 && (f[i + 324] & 1) != 0 && (f[i - 18] & 1 << 3) != 0 && (f[i + 18] & 1 << 2) != 0
                && (f[i - 1] & 1 << 5) != 0 && (f[i + 1] & 1 << 4) != 0;
    }

    /**
     * The section's positions as {@code SectionCompiler.compile} walks them, each one remembered as the block being
     * compiled for {@link #enclosedModel} and {@link #hiddenFluid}; vanilla's own iterable while neither is on.
     */
    public static Iterable<BlockPos> blocks(Iterable<BlockPos> positions) {
        TerrainCompile c = THREAD.get();
        if (!c.compiling || !(c.models || c.fluids)) return positions;
        return () -> {
            Iterator<BlockPos> walk = positions.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return walk.hasNext();
                }

                @Override
                public BlockPos next() {
                    return c.current = walk.next();
                }
            };
        };
    }

    /**
     * {@code ENCLOSED_MODELS}: whether the block being compiled ({@code state}, a model block) draws nothing for certain
     * before its model is even asked for parts — every neighbour is full against it (so all six faces are culled, as in
     * {@link #enclosed}) and every variant of its model has only such faces ({@link CullableModels}). Then vanilla's
     * model data, render layers, seeded variant pick and {@code renderBatched} are all left out: the same mesh (a layer
     * begun for nothing but such blocks builds no mesh either way).
     */
    public static boolean enclosedModel(BlockState state, BlockRenderDispatcher blocks) {
        TerrainCompile c = THREAD.get();
        if (!c.compiling || !c.models || c.current == null || !c.surrounded(c.current)) return false;
        return CullableModels.cullable(state, blocks.getBlockModel(state));
    }

    /**
     * {@code HIDDEN_FLUIDS}: whether the fluid of the block being compiled would draw no face ({@link HiddenFluids}); then
     * vanilla's render layer look-up and {@code renderLiquid} are left out (same mesh, as for {@link #enclosedModel}).
     */
    public static boolean hiddenFluid(BlockAndTintGetter region, FluidState fluid) {
        TerrainCompile c = THREAD.get();
        if (!c.compiling || !c.fluids || c.current == null) return false;
        return HiddenFluids.hidden(region, c.current, fluid, c.scratch);
    }

    /** {@code FACE_GROUPS}: the groups of the compile's solid and cutout quads (RETURN of the compile, before {@link #end}). */
    public static void finish(SectionCompiler.Results results) {
        TerrainCompile c = THREAD.get();
        if (!c.compiling || !c.groups) return;
        for (java.util.Map.Entry<ChunkSectionLayer, MeshData> e : results.renderedLayers.entrySet()) {
            if (!FaceGroups.grouped(e.getKey())) continue;
            MeshData mesh = e.getValue();
            FaceGroups groups = FaceGroups.of(mesh.vertexBuffer(), mesh.drawState().format().getVertexSize());
            if (groups != null) FaceGroups.pending(mesh, groups);
        }
    }

    /** The compile is done (RETURN of {@code SectionCompiler.compile}): nothing of it is kept alive. */
    public static void end() {
        TerrainCompile c = THREAD.get();
        if (c.span != null) c.span.close();
        c.span = null;
        c.compiling = false;
        c.current = null;
        c.faceLevel = null;
        c.faceState = null;
        if (c.light != null) c.light.end();
        if (c.tintMemo != null) c.tintMemo.end();
    }

    /** The compile running on this thread if it meshes the new way ({@link Optimization#SECTION_MESHING}), else null. */
    @Nullable
    public static TerrainCompile meshing() {
        TerrainCompile c = THREAD.get();
        return c.compiling && c.meshing ? c : null;
    }

    /** The biome colour samples of the compile on this thread if it blends the new way, else null. */
    @Nullable
    static BiomeTints.Memo tints() {
        TerrainCompile c = THREAD.get();
        return c.compiling && c.tints ? c.tintMemo : null;
    }

    /** The section's light cache for {@code ModelBlockRenderer.Cache.enable()} (null: vanilla's caches as they are). */
    @Nullable
    static SectionLight light() {
        TerrainCompile c = THREAD.get();
        return c.compiling && c.meshing ? c.light : null;
    }

    /**
     * Whether a face of the block being tesselated was already decided: 1 drawn, 0 culled, -1 not known yet. Vanilla
     * means to decide each face once per block ({@code tesselateWithAO} / {@code tesselateWithoutAO}), but its test
     * {@code (i & k) == 1} only ever holds for DOWN, so every part of a multipart model asks again. The answer is
     * {@code Block.shouldRenderFace(state, region.getBlockState(neighbour), direction)}: a function of the two states
     * and the direction alone (its occlusion cache only remembers results), and the region is the compile's snapshot,
     * so the same question has the same answer for the rest of the block.
     */
    public int face(BlockAndTintGetter level, BlockState state, BlockPos pos, boolean cull, Direction direction, BlockPos neighbour) {
        long p = pos.asLong();
        if (level != faceLevel || state != faceState || p != facePos || cull != faceCull) {
            faceLevel = level;
            faceState = state;
            facePos = p;
            faceCull = cull;
            faceKnown = 0;
            faceRender = 0;
            return -1;
        }
        int d = direction.ordinal();
        if ((faceKnown & 1 << d) == 0 || faceNeighbour[d] != neighbour.asLong()) return -1;
        return (faceRender & 1 << d) != 0 ? 1 : 0;
    }

    /** Remembers a face decided for the block {@link #face} was last asked about. */
    public void faceDecided(Direction direction, BlockPos neighbour, boolean render) {
        int d = direction.ordinal();
        faceKnown |= 1 << d;
        faceNeighbour[d] = neighbour.asLong();
        faceRender = render ? faceRender | 1 << d : faceRender & ~(1 << d);
    }
}
