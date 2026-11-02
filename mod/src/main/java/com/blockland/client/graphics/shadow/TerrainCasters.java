package com.blockland.client.graphics.shadow;

import com.blockland.BlockLand;
import com.blockland.client.perf.Optimization;
import com.blockland.client.perf.gl.PagedDraws;
import com.blockland.client.perf.gl.VertexPages;
import com.blockland.client.render.MeshBounds;
import com.blockland.client.render.TerrainVersion;
import com.blockland.client.render.TerrainAnimation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The terrain casters: the compiled chunk sections around the camera, drawn into the shadow map straight from the GPU
 * buffers the level renderer already holds (solid, cutout-mipped and cutout layers; water and other translucent blocks
 * cast no shadow). The level renderer only knows the sections the camera sees; the sun also needs those behind the
 * camera and above it, so they are looked up in its view area (a private field) by section position.
 * <p>
 * Every section is placed relative to the centre of the cascade it is drawn into ({@link Cascade#casterView}): its
 * transform slot holds its origin minus that centre, and the cascade's projection is fixed with its placement. So which
 * sections reach a cascade and where they land in its tile depend on the placement alone, not on the camera — a cascade
 * kept from an earlier frame ({@code TerrainCache}) is the drawing this frame would make, bit for bit, as long as the
 * same meshes reach it. Each cascade drawn this frame gets its own transform slots, written when it is drawn.
 * <p>
 * Batched ({@link Optimization#SHADOW_BATCHING}): the transforms are reused objects, which cascades each section
 * reaches is worked out once ({@link #mark}), and each cascade submits its solid and its cutout sections as one batch
 * each (the same draws in the same order with the same state, each section's transform bound per draw) instead of one
 * draw call after the other, each setting up all of the pass's state again.
 */
final class TerrainCasters {
    /** More would mean a huge shadow distance on a huge render distance: the farthest are left out. */
    private static final int MAX_SECTIONS = 6144;
    private static final ChunkSectionLayer[] CUTOUT_LAYERS = {ChunkSectionLayer.CUTOUT_MIPPED, ChunkSectionLayer.CUTOUT};
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    /** Bound per draw in a batch: each section's own transform. */
    private static final List<String> PER_DRAW = List.of("DynamicTransforms");
    /** Batch draw slots per section: the solid, cutout-mipped and cutout layers. */
    private static final int SOLID = 0, MIPPED = 1, CUTOUT = 2;

    @Nullable
    private static final Field VIEW_AREA = field();

    private final SectionMesh[] meshes = new SectionMesh[MAX_SECTIONS];
    /** Each gathered section's block origin. */
    private final int[] origins = new int[MAX_SECTIONS * 3];
    /** Per gathered section: its alpha-tested layers draw animated sprites ({@link TerrainAnimation}). */
    private final boolean[] animated = new boolean[MAX_SECTIONS];
    /** Per gathered section: the box round its casters' vertices, section-relative ({@link MeshBounds}), or NaN when unknown. */
    private final float[] boxes = new float[MAX_SECTIONS * 6];
    /** {@code SHADOW_CULLING} this frame: sections are also culled by their box in the light's view ({@link #reaches}). */
    private boolean culling;
    private final float[] boxScratch = new float[6];
    private final int[] sectionRect = new int[4];

    /** Which of a cascade's sections a draw takes: every one, the static ones, or the ones with animated sprites. */
    enum Draws {
        /** Every layer of every section (the whole map at once). */
        ALL,
        /** Everything that holds still between ticks: the solid layers of all sections, the cutout layers of sections without animated sprites. */
        STATIC,
        /** The cutout layers of the sections with animated sprites (the solid layer is never alpha-tested: nothing of it here). */
        ANIMATED
    }
    /** Per cascade and gathered section: the transform slot of the section in that cascade, null where it does not reach. */
    private final GpuBufferSlice[][] slices = new GpuBufferSlice[4][];
    private int count;
    /** Most sequential quad indices a section without its own index buffer needs. */
    private int maxSequentialIndices;
    private boolean warnedFull;
    /** TERRAIN_PAGES: the batched draws go out as paged multi-draws ({@link #drawBatched}), no transforms written. */
    private boolean paged;
    private final PagedDraws.Batch pagedBatch = new PagedDraws.Batch();
    /** {@link #prepare}'s mark of a cascade whose draws need no transforms (paged). */
    private static final GpuBufferSlice[] PAGED = new GpuBufferSlice[0];
    /** SHADOW_GATHER_CACHE: the last walk is kept, and what it read ({@link #gather}). */
    private boolean kept;
    @Nullable
    private Object keptSections;
    private int keptViewDistance, keptSectionsY, keptCount;
    private long keptCamera, keptVersion;
    /**
     * How far (blocks) a cascade's box may move from where the kept walk was made before the walk is made again; the
     * walk takes every section whose sphere touches a box grown by this ({@link Cascade#withinWalk}).
     */
    private static final float GATHER_MARGIN = 16f;
    /** Counts the walks; the sort and the marks remember which walk (and light, placement) they were made for. */
    private int gatherSerial, sortedGather = -1, markedGather = -1;
    private final Vector3f sortedLight = new Vector3f(Float.NaN);
    private final int[] markedSerial = new int[4];
    private boolean markedCulling;
    private float markedPad;
    private final Vector3f scratch = new Vector3f();
    private final Matrix4f inverse = new Matrix4f();
    private DynamicUniforms.Transform[] writing = new DynamicUniforms.Transform[256];

    // batched (SHADOW_BATCHING)
    /** Transforms reused from frame to frame, their model offset set anew for each cascade drawn. */
    private final DynamicUniforms.Transform[] pooled = new DynamicUniforms.Transform[MAX_SECTIONS];
    /** Per gathered section: the cascades drawn this frame it reaches, a bit each. */
    private final byte[] reach = new byte[MAX_SECTIONS];

    // view-culled (SHADOW_VIEW_CASTERS)
    /**
     * Per gathered section: the cascades drawn this frame (a bit each) whose swept view frustum its box reaches
     * ({@link #view}) — every reaching one's bit without the culling.
     */
    private final byte[] view = new byte[MAX_SECTIONS];
    /** Per gathered section: the cascades (a bit each) whose kept static drawing holds it ({@code TerrainCache}, {@link #setCached}). */
    private final byte[] cached = new byte[MAX_SECTIONS];
    private final float[] planes = new float[7 * 4];
    /** {@link #indexOf}: the gathered index of each section key, made for one walk. */
    private final Long2IntOpenHashMap indexByKey = new Long2IntOpenHashMap();
    private int indexGather = -1;
    /**
     * Which of a cascade's reaching sections a draw or a transform write takes ({@link #picks}): every one; those that
     * can shadow something in view ({@link #view}); those the kept static drawing holds; the needed ones it does not
     * hold yet; or anything a frame may draw (needed or kept).
     */
    static final int REACHING = 0, NEEDED = 1, CACHED = 2, NEW = 3, PREPARE = 4;

    // sorted (SHADOW_SORTING)
    /** Gathered sections in drawing order (nearest the sun first), or null to draw them as gathered. */
    @Nullable
    private int[] order;
    private final int[] sorted = new int[MAX_SECTIONS];
    private final long[] keys = new long[MAX_SECTIONS];
    private final List<RenderPass.Draw<GpuBufferSlice[]>> batch = new ArrayList<>();
    /** Per section slot: binds the slot's transform from the cascade's slices. */
    @SuppressWarnings("unchecked")
    private final BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader>[] uploaders = new BiConsumer[MAX_SECTIONS];
    /** Per layer and section slot: an earlier frame's draw, reused while it names the same buffers and range. */
    @SuppressWarnings("unchecked")
    private final RenderPass.Draw<GpuBufferSlice[]>[][] draws = new RenderPass.Draw[3][MAX_SECTIONS];

    private static Field field() {
        try {
            Field f = LevelRenderer.class.getDeclaredField("viewArea");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Level renderer has no readable view area ({}): terrain casts no sun shadows", e.toString());
            return null;
        }
    }

    static boolean supported() {
        return VIEW_AREA != null;
    }

    int count() {
        return count;
    }

    int maxSequentialIndices() {
        return maxSequentialIndices;
    }

    /**
     * Finds the sections that can cast into the cascades: those of the level renderer's view area whose mesh has a
     * casting layer and whose sphere touches a cascade's box — every cascade's ({@code only} null: SHADOW_GATHER_CACHE,
     * the walk is then kept and returned again while nothing it read can have changed: the same view area at the same
     * camera section, no section's mesh or place set since ({@link TerrainVersion}), the cascades placed as they were),
     * or {@code only}'s alone (the outermost cascade drawn this frame, walked every frame as the old path did) — with
     * their meshes, origins, boxes and animated flags. False when the view area cannot be read.
     */
    boolean gather(Level level, Cascade[] cascades, int cascadeCount, @Nullable Cascade only) {
        for (int c = 0; c < 4; c++) slices[c] = null;
        ViewArea area;
        try {
            area = VIEW_AREA == null ? null : (ViewArea) VIEW_AREA.get(Minecraft.getInstance().levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        if (area == null || area.sections == null) {
            count = 0;
            maxSequentialIndices = 0;
            order = null;
            kept = false;
            return true;
        }
        int viewDistance = area.getViewDistance();
        int sizeXZ = viewDistance * 2 + 1;
        int sizeY = level.getSectionsCount();
        SectionRenderDispatcher.RenderSection[] sections = area.sections;
        SectionPos cameraSection = area.getCameraSectionPos();
        long version = TerrainVersion.current();
        boolean cache = only == null;
        if (cache && kept && keptSections == sections && keptViewDistance == viewDistance && keptSectionsY == sizeY
                && keptCamera == cameraSection.asLong() && keptVersion == version && keptCount == cascadeCount && withinWalk(cascades, cascadeCount)) {
            // no section holds anything else, and every box still lies within the margin the walk allowed for
            return true;
        }
        count = 0;
        maxSequentialIndices = 0;
        order = null;
        kept = false;
        gatherSerial++;
        if (sections.length != sizeXZ * sizeY * sizeXZ) return true;
        int minSectionY = level.getMinSectionY(), maxSectionY = level.getMaxSectionY();

        // the section range: the boxes' world bounds (relative to their centres), from their eight corners, grown by the
        // margin the kept walk allows the boxes to move within
        int from = cache ? 0 : -1, to = cache ? cascadeCount : 0;
        float margin = cache ? GATHER_MARGIN : 0f;
        int sx0 = Integer.MAX_VALUE, sx1 = Integer.MIN_VALUE, sy0 = Integer.MAX_VALUE, sy1 = Integer.MIN_VALUE;
        int sz0 = Integer.MAX_VALUE, sz1 = Integer.MIN_VALUE;
        for (int c = from; c < to; c++) {
            Cascade bounds = c < 0 ? only : cascades[c];
            if (!bounds.valid) continue;
            inverse.set(bounds.casterView).invert();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                inverse.transformPosition((i & 1) == 0 ? -bounds.half : bounds.half, (i & 2) == 0 ? -bounds.half : bounds.half,
                        (i & 4) == 0 ? 0 : -bounds.depth, scratch);
                minX = Math.min(minX, scratch.x);
                minY = Math.min(minY, scratch.y);
                minZ = Math.min(minZ, scratch.z);
                maxX = Math.max(maxX, scratch.x);
                maxY = Math.max(maxY, scratch.y);
                maxZ = Math.max(maxZ, scratch.z);
            }
            sx0 = Math.min(sx0, SectionPos.blockToSectionCoord(bounds.centerX + minX - margin));
            sx1 = Math.max(sx1, SectionPos.blockToSectionCoord(bounds.centerX + maxX + margin));
            sy0 = Math.min(sy0, SectionPos.blockToSectionCoord(bounds.centerY + minY - margin));
            sy1 = Math.max(sy1, SectionPos.blockToSectionCoord(bounds.centerY + maxY + margin));
            sz0 = Math.min(sz0, SectionPos.blockToSectionCoord(bounds.centerZ + minZ - margin));
            sz1 = Math.max(sz1, SectionPos.blockToSectionCoord(bounds.centerZ + maxZ + margin));
        }
        sx0 = Math.max(cameraSection.x() - viewDistance, sx0);
        sx1 = Math.min(cameraSection.x() + viewDistance, sx1);
        sy0 = Math.max(minSectionY, sy0);
        sy1 = Math.min(maxSectionY, sy1);
        sz0 = Math.max(cameraSection.z() - viewDistance, sz0);
        sz1 = Math.min(cameraSection.z() + viewDistance, sz1);

        for (int sz = sz0; sz <= sz1; sz++) {
            int zi = Math.floorMod(sz, sizeXZ);
            for (int sx = sx0; sx <= sx1; sx++) {
                int xi = Math.floorMod(sx, sizeXZ);
                for (int sy = sy0; sy <= sy1; sy++) {
                    SectionRenderDispatcher.RenderSection section = sections[(zi * sizeY + (sy - minSectionY)) * sizeXZ + xi];
                    if (section == null || section.getSectionNode() != SectionPos.asLong(sx, sy, sz)) continue;
                    SectionMesh mesh = section.getSectionMesh();
                    if (!casts(mesh)) continue;
                    BlockPos origin = section.getRenderOrigin();
                    boolean touches = false;
                    for (int c = from; c < to && !touches; c++) {
                        Cascade bounds = c < 0 ? only : cascades[c];
                        touches = bounds.valid && bounds.touches((float) (origin.getX() + 8 - bounds.centerX), (float) (origin.getY() + 8 - bounds.centerY),
                                (float) (origin.getZ() + 8 - bounds.centerZ), Cascade.SECTION_RADIUS + margin, scratch);
                    }
                    if (!touches) continue;
                    if (count == MAX_SECTIONS) {
                        if (!warnedFull) {
                            warnedFull = true;
                            BlockLand.LOGGER.warn("[BlockLand] More than {} sections cast sun shadows; the rest are left out", MAX_SECTIONS);
                        }
                        continue;
                    }
                    meshes[count] = mesh;
                    animated[count] = TerrainAnimation.animatedCutout(mesh);
                    if (MeshBounds.of(mesh, boxScratch)) {
                        System.arraycopy(boxScratch, 0, boxes, count * 6, 6);
                    } else {
                        java.util.Arrays.fill(boxes, count * 6, count * 6 + 6, Float.NaN);
                    }
                    origins[count * 3] = origin.getX();
                    origins[count * 3 + 1] = origin.getY();
                    origins[count * 3 + 2] = origin.getZ();
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        SectionBuffers buffers = layer == ChunkSectionLayer.TRANSLUCENT || layer == ChunkSectionLayer.TRIPWIRE
                                ? null : mesh.getBuffers(layer);
                        if (buffers != null && buffers.getIndexBuffer() == null) {
                            maxSequentialIndices = Math.max(maxSequentialIndices, buffers.getIndexCount());
                        }
                    }
                    count++;
                }
            }
        }
        if (cache) {
            kept = true;
            keptSections = sections;
            keptViewDistance = viewDistance;
            keptSectionsY = sizeY;
            keptCamera = cameraSection.asLong();
            keptVersion = version;
            keptCount = cascadeCount;
            for (int c = 0; c < cascadeCount; c++) cascades[c].walked();
        }
        return true;
    }

    /** Whether every cascade's box still lies within the margin of where it was when the kept walk was made. */
    private boolean withinWalk(Cascade[] cascades, int cascadeCount) {
        for (int c = 0; c < cascadeCount; c++) if (!cascades[c].withinWalk(GATHER_MARGIN)) return false;
        return true;
    }

    /**
     * Which of the cascades drawn this frame each gathered section reaches, the test {@link #draw} makes per layer made
     * once (the batched draws and the kept terrain's signatures read it).
     *
     * @param pad   the padding of a block box for a mesh without a measured box ({@link #reaches}), or negative for none
     * @param cache SHADOW_GATHER_CACHE: every cascade's marks are made (drawn or not) and kept while the walk and the
     *              cascade's placement are kept; otherwise the drawn cascades' marks are made anew every frame
     */
    void mark(Cascade[] cascades, boolean[] due, int cascadeCount, float pad, boolean cache) {
        if (!cache) {
            markedGather = -1;
            for (int i = 0; i < count; i++) {
                int bits = 0;
                for (int c = 0; c < cascadeCount; c++) {
                    if (due[c] && reaches(cascades[c], i, pad)) bits |= 1 << c;
                }
                reach[i] = (byte) bits;
            }
            return;
        }
        boolean fresh = markedGather != gatherSerial || markedCulling != culling || markedPad != pad;
        if (fresh) java.util.Arrays.fill(reach, 0, count, (byte) 0);
        for (int c = 0; c < cascadeCount; c++) {
            if (!fresh && markedSerial[c] == cascades[c].serial) continue;
            int bit = 1 << c, keep = ~bit;
            Cascade cascade = cascades[c];
            for (int i = 0; i < count; i++) reach[i] = (byte) ((reach[i] & keep) | (reaches(cascade, i, pad) ? bit : 0));
            markedSerial[c] = cascade.serial;
        }
        markedGather = gatherSerial;
        markedCulling = culling;
        markedPad = pad;
    }

    /** Counts the walks ({@link #gather}): the same number means the same sections in the same slots with the same meshes. */
    int gatherSerial() {
        return gatherSerial;
    }

    /** The gathered index of the section with this {@link #key}, or −1 (made once per walk). */
    int indexOf(long key) {
        if (indexGather != gatherSerial) {
            indexByKey.clear();
            indexByKey.defaultReturnValue(-1);
            for (int i = 0; i < count; i++) indexByKey.put(key(origins[i * 3], origins[i * 3 + 1], origins[i * 3 + 2]), i);
            indexGather = gatherSerial;
        }
        return indexByKey.get(key);
    }

    /**
     * SHADOW_VIEW_CASTERS: of the cascades drawn this frame, which ones each gathered section can shadow something in
     * view of ({@link ViewVolume}: the box round its mesh against the cascade's view frustum swept towards the light;
     * a mesh without a box is never left out). Without a volume (the culling off, a second view of the world this
     * frame) every section it reaches counts. Made after {@link #mark}.
     *
     * @param readRange how far from the camera the map is read this frame at most (blocks; the volumetric light's march
     *                  range while the world is unlit, else unbounded)
     */
    void view(Cascade[] cascades, boolean[] due, int cascadeCount, @Nullable ViewVolume volume, double camX, double camY, double camZ, float readRange) {
        for (int c = 0; c < cascadeCount; c++) {
            if (!due[c]) continue;
            int bit = 1 << c, keep = ~bit;
            Cascade cascade = cascades[c];
            // the cascade's receivers lie within its reach — and no farther than anything reads the map this frame
            int n = volume == null ? 0 : volume.sweep(cascade.light, cascade.centerX, cascade.centerY, cascade.centerZ, camX, camY, camZ,
                    Math.min(cascade.split, readRange), planes);
            for (int i = 0; i < count; i++) {
                boolean in = (reach[i] & bit) != 0;
                int b = i * 6;
                if (in && volume != null && !Float.isNaN(boxes[b])) {
                    float ox = (float) (origins[i * 3] - cascade.centerX), oy = (float) (origins[i * 3 + 1] - cascade.centerY);
                    float oz = (float) (origins[i * 3 + 2] - cascade.centerZ);
                    in = ViewVolume.reaches(planes, n, ox + boxes[b], oy + boxes[b + 1], oz + boxes[b + 2], ox + boxes[b + 3], oy + boxes[b + 4], oz + boxes[b + 5]);
                }
                view[i] = (byte) ((view[i] & keep) | (in ? bit : 0));
            }
        }
    }

    /** Whether gathered section {@code i} is taken for a draw of {@code mode} in cascade {@code c} (see the modes). */
    boolean picks(int i, int c, int mode) {
        return pick(i, 1 << c, mode);
    }

    private boolean pick(int i, int bit, int mode) {
        if ((reach[i] & bit) == 0) return false;
        return switch (mode) {
            case REACHING -> true;
            case NEEDED -> (view[i] & bit) != 0;
            case CACHED -> (cached[i] & bit) != 0;
            case NEW -> (view[i] & bit) != 0 && (cached[i] & bit) == 0;
            default -> (view[i] & bit) != 0 || (cached[i] & bit) != 0;
        };
    }

    /** Whether gathered section {@code i} reaches cascade {@code c} ({@link #mark}ed). */
    boolean reaches(int i, int c) {
        return (reach[i] & (1 << c)) != 0;
    }

    /** Cascade {@code c}'s kept static drawing holds gathered section {@code i} ({@code TerrainCache}). */
    void setCached(int i, int c, boolean in) {
        int bit = 1 << c;
        cached[i] = (byte) (in ? cached[i] | bit : cached[i] & ~bit);
    }

    /** Nothing of cascade {@code c}'s kept static drawing is known among the gathered sections. */
    void clearCached(int c) {
        int keep = ~(1 << c);
        for (int i = 0; i < count; i++) cached[i] = (byte) (cached[i] & keep);
    }

    SectionMesh mesh(int i) {
        return meshes[i];
    }

    /** Gathered section {@code i}'s block origin (x, y, z) into {@code out}. */
    void origin(int i, int[] out) {
        out[0] = origins[i * 3];
        out[1] = origins[i * 3 + 1];
        out[2] = origins[i * 3 + 2];
    }

    /** Gathered section {@code i}'s mesh box (six floats, NaN when unknown) into {@code out}. */
    void box(int i, float[] out) {
        System.arraycopy(boxes, i * 6, out, 0, 6);
    }

    boolean boxKnown(int i) {
        return !Float.isNaN(boxes[i * 6]);
    }

    /**
     * The atlas texels every section a draw of {@code mode} takes in cascade {@code c} can touch, as one rectangle into
     * {@code rect} (the whole tile once a box is unknown); false when the draw takes none (the rect is then empty).
     */
    boolean rectOf(int c, int mode, Cascade cascade, int[] rect) {
        int bit = 1 << c;
        rect[2] = rect[3] = 0;
        boolean any = false;
        for (int i = 0; i < count; i++) {
            if (!pick(i, bit, mode)) continue;
            any = true;
            if (rect(i, cascade, sectionRect)) Cascade.union(rect, sectionRect);
        }
        return any;
    }

    /**
     * Sorted: the gathered sections are drawn nearest the sun first (by their centre's depth in the light's view, the
     * same for every cascade), so what lies beneath — the ground under the trees, the caves under the ground — fails the
     * GPU's early depth test instead of being rasterised, shaded and written over. The order cannot change a texel: every
     * caster writes depth with a less-or-equal test and nothing else (no colour, no blending), so each texel ends at the
     * least depth of the fragments covering it, whatever order they came in. Nothing but the order of the same draws
     * changes.
     *
     * @param along any cascade (they share the light's direction): its view gives the depth along the light
     */
    void sort(Cascade along) {
        if (count == 0) return;
        // the kept walk under the same light is already in order (SHADOW_GATHER_CACHE; the order only depends on the
        // light's direction — and it cannot change a texel either way)
        if (order != null && sortedGather == gatherSerial && sortedLight.equals(along.light)) return;
        Matrix4f view = along.casterView;
        for (int i = 0; i < count; i++) {
            float x = (float) (origins[i * 3] + 8 - along.centerX), y = (float) (origins[i * 3 + 1] + 8 - along.centerY);
            float z = (float) (origins[i * 3 + 2] + 8 - along.centerZ);
            // view-space depth: the light looks down -z, so the nearest has the largest z; sort ascending on -z
            float depth = -(view.m02() * x + view.m12() * y + view.m22() * z + view.m32());
            int bits = Float.floatToIntBits(depth);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            keys[i] = ((long) bits << 32) | i;
        }
        java.util.Arrays.sort(keys, 0, count);
        for (int n = 0; n < count; n++) sorted[n] = (int) keys[n];
        order = sorted;
        sortedGather = gatherSerial;
        sortedLight.set(along.light);
    }

    /** The section drawn {@code n}th. */
    private int at(int n) {
        return order == null ? n : order[n];
    }

    /**
     * How far the farthest corner of any section reaching cascade {@code c} ({@link #mark}ed) lies from the cascade's
     * centre (blocks): its mesh box, or its block box grown by two blocks for a mesh without one ({@link Cascade#reach}).
     */
    float extent(int c, Cascade cascade) {
        int bit = 1 << c;
        double far = 0;
        for (int i = 0; i < count; i++) {
            if ((reach[i] & bit) == 0) continue;
            double ox = origins[i * 3] - cascade.centerX, oy = origins[i * 3 + 1] - cascade.centerY, oz = origins[i * 3 + 2] - cascade.centerZ;
            int b = i * 6;
            boolean known = !Float.isNaN(boxes[b]);
            float x0 = known ? boxes[b] : -2, y0 = known ? boxes[b + 1] : -2, z0 = known ? boxes[b + 2] : -2;
            float x1 = known ? boxes[b + 3] : 18, y1 = known ? boxes[b + 4] : 18, z1 = known ? boxes[b + 5] : 18;
            double dx = Math.max(Math.abs(ox + x0), Math.abs(ox + x1)), dy = Math.max(Math.abs(oy + y0), Math.abs(oy + y1));
            double dz = Math.max(Math.abs(oz + z0), Math.abs(oz + z1));
            far = Math.max(far, dx * dx + dy * dy + dz * dz);
        }
        return (float) Math.sqrt(far);
    }

    /** A section's place as one number that orders like {@link #gather} walks (z, then x, then y, each ascending). */
    static long key(int originX, int originY, int originZ) {
        long off = 1L << 20;
        return (((originZ >> 4) + off) << 42) | (((originX >> 4) + off) << 21) | ((originY >> 4) + off);
    }

    /** The block origin of a {@link #key}: x, y, z into {@code out}. */
    static void origin(long key, int[] out) {
        long off = 1L << 20;
        out[2] = (int) ((key >>> 42) - off) << 4;
        out[1] = (int) ((key & ((1L << 21) - 1)) - off) << 4;
        out[0] = (int) (((key >>> 21) & ((1L << 21) - 1)) - off) << 4;
    }

    /**
     * The atlas texels of a cascade's tile that a section at {@code origin} with the box {@code box} (at {@code at}) can
     * touch (the whole tile for a NaN box) — the test {@link #rect} makes, for a section not gathered now.
     */
    boolean rectOf(Cascade cascade, int originX, int originY, int originZ, float[] box, int at, int[] out) {
        if (Float.isNaN(box[at])) {
            cascade.tileRect(out);
            return true;
        }
        float x = (float) (originX - cascade.centerX), y = (float) (originY - cascade.centerY), z = (float) (originZ - cascade.centerZ);
        return cascade.tileRect(cascade.terrainRender, x + box[at], y + box[at + 1], z + box[at + 2], x + box[at + 3], y + box[at + 4],
                z + box[at + 5], out, scratch);
    }

    /** Whether the sections are culled by their boxes this frame ({@code SHADOW_CULLING}), besides their spheres. */
    void culling(boolean on) {
        culling = on;
    }

    /** Whether the batched draws go out as paged multi-draws this frame ({@code TERRAIN_PAGES} with the batches). */
    void paged(boolean on) {
        paged = on;
    }

    /**
     * Whether gathered section {@code i} can cast into the cascade: by its sphere, and ({@link #culling}) by the box
     * round its mesh's vertices ({@link MeshBounds}) — or, for a mesh without one, by its block box grown by
     * {@code pad} (how far block models reach out of their blocks, {@link Cascade#boxReaches}; negative: unknown, the
     * sphere alone then).
     */
    private boolean reaches(Cascade cascade, int i, float pad) {
        float x = (float) (origins[i * 3] + 8 - cascade.centerX), y = (float) (origins[i * 3 + 1] + 8 - cascade.centerY);
        float z = (float) (origins[i * 3 + 2] + 8 - cascade.centerZ);
        if (!cascade.touches(x, y, z, Cascade.SECTION_RADIUS, scratch)) return false;
        if (!culling) return true;
        int b = i * 6;
        if (!Float.isNaN(boxes[b])) {
            float ox = x - 8, oy = y - 8, oz = z - 8;
            return cascade.meshReaches(ox + boxes[b], oy + boxes[b + 1], oz + boxes[b + 2], ox + boxes[b + 3], oy + boxes[b + 4], oz + boxes[b + 5], scratch);
        }
        return pad < 0 || cascade.boxReaches(scratch);
    }

    private static boolean casts(SectionMesh mesh) {
        return mesh.getBuffers(ChunkSectionLayer.SOLID) != null || mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED) != null
                || mesh.getBuffers(ChunkSectionLayer.CUTOUT) != null;
    }

    /**
     * Writes the transforms of the sections drawn into cascade {@code c} — each one's origin relative to the cascade's
     * centre — once per frame, before the render pass that draws the cascade is begun (a uniform write maps a buffer,
     * which no open pass allows). Which sections: those reaching it by {@link #reaches} ({@code marked}: as
     * {@link #mark} found them) that a draw of {@code mode} takes ({@link #picks}; marked only).
     *
     * @param batched the transforms are pooled objects (the unbatched path makes new ones, as it always did)
     */
    void prepare(int c, Cascade cascade, boolean marked, float pad, boolean batched, int mode) {
        if (slices[c] != null) return;
        if (batched && paged) {
            // the paged batch carries each section's offset itself
            slices[c] = PAGED;
            return;
        }
        GpuBufferSlice[] out = slices[c] = new GpuBufferSlice[count];
        int bit = 1 << c, n = 0;
        if (writing.length < count) writing = new DynamicUniforms.Transform[count];
        for (int i = 0; i < count; i++) {
            if (marked ? !pick(i, bit, mode) : !reaches(cascade, i, pad)) continue;
            float ox = (float) (origins[i * 3] - cascade.centerX), oy = (float) (origins[i * 3 + 1] - cascade.centerY);
            float oz = (float) (origins[i * 3 + 2] - cascade.centerZ);
            DynamicUniforms.Transform transform;
            if (batched) {
                transform = pooled[n];
                if (transform == null) transform = pooled[n] = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(), IDENTITY, 1f);
                ((Vector3f) transform.modelOffset()).set(ox, oy, oz);
            } else {
                transform = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(ox, oy, oz), IDENTITY, 1f);
            }
            writing[n++] = transform;
            // the slot's index among the written ones is kept in the slice array's null pattern: filled in below
            out[i] = PLACEHOLDER;
        }
        if (n == 0) return;
        DynamicUniforms.Transform[] used = new DynamicUniforms.Transform[n];
        System.arraycopy(writing, 0, used, 0, n);
        if (batched) {
            // the uniform storage keeps the last one to compare the next with until the frame ends: never a pooled one
            used[n - 1] = new DynamicUniforms.Transform(IDENTITY, WHITE, new Vector3f(used[n - 1].modelOffset()), IDENTITY, 1f);
        }
        GpuBufferSlice[] written = RenderSystem.getDynamicUniforms().writeTransforms(used);
        for (int i = 0, k = 0; i < count; i++) if (out[i] != null) out[i] = written[k++];
        java.util.Arrays.fill(writing, 0, n, null);
    }

    /** Marks a slot as written in {@link #prepare} until the real slices come back (never bound). */
    private static final GpuBufferSlice PLACEHOLDER = new GpuBufferSlice(null, 0, 0);

    /** Whether gathered section {@code i} is drawn for {@code which} of a cascade's draws of the given kind of layer. */
    private boolean takes(int i, Draws which, boolean cutout) {
        return switch (which) {
            case ALL -> true;
            case STATIC -> !cutout || !animated[i];
            case ANIMATED -> cutout && animated[i];
        };
    }

    /**
     * The atlas texels of cascade {@code c}'s tile that gathered section {@code i}'s casters can touch: the box round its
     * vertices ({@link MeshBounds}; the whole tile when unknown) through the cascade's terrain projection.
     */
    boolean rect(int i, Cascade cascade, int[] out) {
        return rectOf(cascade, origins[i * 3], origins[i * 3 + 1], origins[i * 3 + 2], boxes, i * 6, out);
    }

    /** Whether any section with animated sprites that a draw of {@code mode} takes reaches cascade {@code c} ({@link #mark}ed). */
    boolean hasAnimated(int c, int mode) {
        int bit = 1 << c;
        for (int i = 0; i < count; i++) if (animated[i] && pick(i, bit, mode)) return true;
        return false;
    }

    /**
     * The atlas texels every animated section of cascade {@code c} that a draw of {@code mode} takes ({@link #mark}ed)
     * can touch, as one rectangle into {@code rect}; false when there is none (the rect is then empty).
     */
    boolean animatedRect(int c, int mode, Cascade cascade, int[] rect) {
        int bit = 1 << c;
        rect[2] = rect[3] = 0;
        boolean any = false;
        for (int i = 0; i < count; i++) {
            if (!animated[i] || !pick(i, bit, mode)) continue;
            any = true;
            if (rect(i, cascade, sectionRect)) Cascade.union(rect, sectionRect);
        }
        return any;
    }

    /**
     * Draws the gathered sections that reach cascade {@code c} ({@link #prepare}d): the solid layer, or (with the
     * alpha-tested pipeline set and the block atlas bound) the cutout layers — of the sections {@code which} takes.
     *
     * @param touching only the sections whose casters can touch these atlas texels (null: all of them)
     * @param mode     which of the reaching sections ({@link #picks})
     */
    int draw(RenderPass pass, int c, boolean cutout, Draws which, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType,
             @Nullable int[] touching, Cascade cascade, int mode) {
        GpuBufferSlice[] slices = this.slices[c];
        if (slices == null || slices.length < count) return 0;
        int bit = 1 << c;
        int drawn = 0;
        for (int n = 0; n < count; n++) {
            int i = at(n);
            GpuBufferSlice transform = slices[i];
            if (transform == null || !pick(i, bit, mode) || !takes(i, which, cutout)) continue;
            if (touching != null && !(rect(i, cascade, sectionRect) && Cascade.intersects(sectionRect, touching))) continue;
            SectionMesh mesh = meshes[i];
            boolean bound = false;
            if (cutout) {
                for (ChunkSectionLayer layer : CUTOUT_LAYERS) {
                    bound = drawLayer(pass, mesh.getBuffers(layer), transform, bound, sequential, sequentialType);
                }
            } else {
                bound = drawLayer(pass, mesh.getBuffers(ChunkSectionLayer.SOLID), transform, false, sequential, sequentialType);
            }
            if (bound) drawn++;
        }
        return drawn;
    }

    /**
     * Batched {@link #draw}: the sections {@link #mark} found reaching the cascade, the same layers in the same order,
     * submitted as one batch (the pipeline, its other uniforms and the block atlas set on the pass as for {@link #draw}).
     */
    int drawBatched(RenderPass pass, int c, boolean cutout, Draws which, @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType,
                    @Nullable int[] touching, Cascade cascade, int mode) {
        GpuBufferSlice[] slices = this.slices[c];
        if (slices == null) return 0;
        if (paged) return drawPaged(pass, c, cutout, which, sequential, sequentialType, touching, cascade, mode);
        int bit = 1 << c;
        int drawn = 0;
        batch.clear();
        for (int n = 0; n < count; n++) {
            int i = at(n);
            if (!pick(i, bit, mode) || !takes(i, which, cutout)) continue;
            if (touching != null && !(rect(i, cascade, sectionRect) && Cascade.intersects(sectionRect, touching))) continue;
            SectionMesh mesh = meshes[i];
            boolean any;
            if (cutout) {
                any = add(mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED), MIPPED, i, sequential, sequentialType);
                any |= add(mesh.getBuffers(ChunkSectionLayer.CUTOUT), CUTOUT, i, sequential, sequentialType);
            } else {
                any = add(mesh.getBuffers(ChunkSectionLayer.SOLID), SOLID, i, sequential, sequentialType);
            }
            if (any) drawn++;
        }
        if (!batch.isEmpty()) pass.drawMultipleIndexed(batch, null, null, PER_DRAW, slices);
        batch.clear();
        return drawn;
    }

    /**
     * {@link #drawBatched} as paged multi-draws (TERRAIN_PAGES): the same sections in the same order, each from its base
     * vertex in its page (or its own buffer), placed by its offset in the batch — what {@link #prepare}'s transform
     * would hold — read by draw id with the {@code BL_PAGES} pipelines.
     */
    private int drawPaged(RenderPass pass, int c, boolean cutout, Draws which, @Nullable GpuBuffer sequential,
                          @Nullable VertexFormat.IndexType sequentialType, @Nullable int[] touching, Cascade cascade, int mode) {
        int bit = 1 << c;
        int drawn = 0;
        PagedDraws.Batch batch = pagedBatch;
        batch.clear();
        for (int n = 0; n < count; n++) {
            int i = at(n);
            if (!pick(i, bit, mode) || !takes(i, which, cutout)) continue;
            if (touching != null && !(rect(i, cascade, sectionRect) && Cascade.intersects(sectionRect, touching))) continue;
            SectionMesh mesh = meshes[i];
            float ox = (float) (origins[i * 3] - cascade.centerX), oy = (float) (origins[i * 3 + 1] - cascade.centerY);
            float oz = (float) (origins[i * 3 + 2] - cascade.centerZ);
            boolean any;
            if (cutout) {
                any = addPaged(batch, mesh.getBuffers(ChunkSectionLayer.CUTOUT_MIPPED), ox, oy, oz);
                any |= addPaged(batch, mesh.getBuffers(ChunkSectionLayer.CUTOUT), ox, oy, oz);
            } else {
                any = addPaged(batch, mesh.getBuffers(ChunkSectionLayer.SOLID), ox, oy, oz);
            }
            if (any) drawn++;
        }
        if (!batch.isEmpty()) batch.draw((GlRenderPass) pass, sequential, sequentialType);
        batch.clear();
        return drawn;
    }

    private static boolean addPaged(PagedDraws.Batch batch, @Nullable SectionBuffers buffers, float ox, float oy, float oz) {
        if (buffers == null || buffers.getIndexCount() == 0) return false;
        batch.add(buffers.getVertexBuffer(), buffers.getIndexBuffer(), buffers.getIndexType(), 0, buffers.getIndexCount(),
                VertexPages.baseVertex(buffers), ox, oy, oz);
        return true;
    }

    /** One layer's draw for the batch, as {@link #drawLayer} would make it (same buffers, index range and type). */
    private boolean add(@Nullable SectionBuffers buffers, int layer, int i, @Nullable GpuBuffer sequential,
                        @Nullable VertexFormat.IndexType sequentialType) {
        if (buffers == null || buffers.getIndexCount() == 0) return false;
        GpuBuffer indices = buffers.getIndexBuffer();
        VertexFormat.IndexType type = buffers.getIndexType();
        if (indices == null) {
            if (sequential == null || sequentialType == null) return false;
            indices = sequential;
            type = sequentialType;
        }
        GpuBuffer vertices = buffers.getVertexBuffer();
        int indexCount = buffers.getIndexCount();
        RenderPass.Draw<GpuBufferSlice[]> draw = draws[layer][i];
        if (draw == null || draw.vertexBuffer() != vertices || draw.indexBuffer() != indices || draw.indexType() != type
                || draw.indexCount() != indexCount) {
            draw = draws[layer][i] = new RenderPass.Draw<>(0, vertices, indices, type, 0, indexCount, uploader(i));
        }
        batch.add(draw);
        return true;
    }

    private BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader(int i) {
        BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader = uploaders[i];
        if (uploader == null) uploader = uploaders[i] = (cascade, upload) -> upload.upload("DynamicTransforms", cascade[i]);
        return uploader;
    }

    private static boolean drawLayer(RenderPass pass, @Nullable SectionBuffers buffers, GpuBufferSlice transform, boolean bound,
                                     @Nullable GpuBuffer sequential, @Nullable VertexFormat.IndexType sequentialType) {
        if (buffers == null || buffers.getIndexCount() == 0) return bound;
        if (buffers.getIndexBuffer() != null) {
            pass.setIndexBuffer(buffers.getIndexBuffer(), buffers.getIndexType());
        } else if (sequential != null && sequentialType != null) {
            pass.setIndexBuffer(sequential, sequentialType);
        } else {
            return bound;
        }
        if (!bound) pass.setUniform("DynamicTransforms", transform);
        pass.setVertexBuffer(0, buffers.getVertexBuffer());
        // from the layer's base vertex: 0 in a buffer of its own, its range's in a page (TERRAIN_PAGES)
        pass.drawIndexed(VertexPages.baseVertex(buffers), 0, buffers.getIndexCount(), 1);
        return true;
    }

    /** Drops the references to this frame's meshes (they belong to the level renderer) — unless the walk is kept. */
    void clear() {
        for (int c = 0; c < 4; c++) slices[c] = null;
        if (kept) return;
        for (int i = 0; i < count; i++) meshes[i] = null;
        count = 0;
    }
}
