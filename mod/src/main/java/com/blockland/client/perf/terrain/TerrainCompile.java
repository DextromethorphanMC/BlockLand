package com.blockland.client.perf.terrain;

import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.Optimization;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * One chunk section compile ({@code SectionCompiler.compile}) on the current thread: the terrain switches as they were
 * when it started and the scratch state of the optimized paths. Compiles run on vanilla's worker threads (and on the
 * render thread for sections the player changed), so the switches are read once here and kept for the whole section —
 * flipping one meanwhile never mixes the old and the new path inside a section (the flip rebuilds every section anyway).
 * <p>
 * What lives here exists per thread and is valid for one compile: the section's light and shade cache
 * ({@link SectionLight}), the biome colour samples ({@link BiomeTints}), the face culling already decided for the block
 * being tesselated, and the vectors {@link QuadWriter} writes through.
 */
public final class TerrainCompile {
    private static final ThreadLocal<TerrainCompile> THREAD = ThreadLocal.withInitial(TerrainCompile::new);

    private boolean compiling;
    private boolean meshing;
    private boolean tints;
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
        // render-thread compiles only (sections the player changed); worker compiles are timed by MeshCheck
        c.span = FrameTimings.span("bl/terrain/compile");
    }

    /** The compile is done (RETURN of {@code SectionCompiler.compile}): nothing of it is kept alive. */
    public static void end() {
        TerrainCompile c = THREAD.get();
        if (c.span != null) c.span.close();
        c.span = null;
        c.compiling = false;
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
