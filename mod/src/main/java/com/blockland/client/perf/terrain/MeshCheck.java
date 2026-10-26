package com.blockland.client.perf.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The terrain optimizations' self-test (rendering spec §20): sections around the camera meshed twice, once with every
 * terrain entry forced off (vanilla's code: snapshot, light, tints, quads) and once forced on, compared byte for byte —
 * vertices and indices of every layer, the translucent layer's sort state, the block entities and the visibility set.
 * Both runs of a section start from the same moment of the world (render thread, nothing in between) and each takes its
 * own snapshot ({@code RenderRegionCache}) under its setting, so the snapshot copies are tested too; the tint caches
 * are cleared before each run so both blend every biome colour themselves (the live game simply works them out again).
 * It also times both runs (snapshot + compile), a direct measure of meshing speed. A run that throws counts as a
 * differing section (the exception is its difference).
 * <p>
 * Render thread, in a world. Everything is compiled into buffers of its own and thrown away: the live section meshes,
 * the compile queue and the worker threads are not touched (they keep the player's switches meanwhile).
 */
public final class MeshCheck {
    /** What {@link #run} found: sections checked and differing, the first difference, milliseconds per variant. */
    public record Result(int sectionsChecked, int sectionsDiffering, @Nullable String firstDifference, double offMillis, double onMillis) {
    }

    private static final int SEARCH_RADIUS = 6;

    private MeshCheck() {
    }

    /** Meshes up to {@code maxSections} non-empty loaded sections nearest the camera both ways and compares them. */
    public static Result run(int maxSections) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !RenderSystem.isOnRenderThread()) return new Result(0, 0, "no world (or not the render thread)", 0, 0);
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        List<SectionPos> sections = pick(level, camera, maxSections);
        SectionCompiler compiler = new SectionCompiler(mc.getBlockRenderer(), mc.getBlockEntityRenderDispatcher());

        long offNanos = 0, onNanos = 0;
        int differing = 0;
        String first = null;
        try (SectionBufferBuilderPack offBuffers = new SectionBufferBuilderPack();
             SectionBufferBuilderPack onBuffers = new SectionBufferBuilderPack()) {
            for (int n = 0; n < sections.size(); n++) {
                SectionPos section = sections.get(n);
                VertexSorting sorting = VertexSorting.byDistance((float) (camera.x - section.minBlockX()),
                        (float) (camera.y - section.minBlockY()), (float) (camera.z - section.minBlockZ()));
                Run off = null, on = null;
                try {
                    // alternate which goes first, so warming up favours neither
                    if ((n & 1) == 0) {
                        off = compile(false, level, compiler, section, sorting, offBuffers);
                        on = compile(true, level, compiler, section, sorting, onBuffers);
                    } else {
                        on = compile(true, level, compiler, section, sorting, onBuffers);
                        off = compile(false, level, compiler, section, sorting, offBuffers);
                    }
                    offNanos += off.nanos;
                    onNanos += on.nanos;
                    String difference = compare(off.results, on.results);
                    if (difference != null) {
                        differing++;
                        if (first == null) first = "section " + section.x() + " " + section.y() + " " + section.z() + ": " + difference;
                    }
                } catch (RuntimeException e) {
                    differing++;
                    if (first == null) first = "section " + section.x() + " " + section.y() + " " + section.z() + ": " + e.getMessage();
                } finally {
                    if (off != null) off.results.release();
                    if (on != null) on.results.release();
                    offBuffers.discardAll();
                    onBuffers.discardAll();
                }
            }
        }
        return new Result(sections.size(), differing, first, offNanos / 1.0e6, onNanos / 1.0e6);
    }

    private record Run(SectionCompiler.Results results, long nanos) {
    }

    private static Run compile(boolean on, ClientLevel level, SectionCompiler compiler, SectionPos section, VertexSorting sorting,
                               SectionBufferBuilderPack buffers) {
        TerrainSwitches.force(on);
        try {
            level.clearTintCaches();
            long start = System.nanoTime();
            RenderSectionRegion region = new RenderRegionCache().createRegion(level, section.asLong());
            SectionCompiler.Results results = compiler.compile(section, region, sorting, buffers);
            return new Run(results, System.nanoTime() - start);
        } catch (RuntimeException e) {
            // what a finished compile would have reset on this thread
            TerrainCompile.end();
            ModelBlockRenderer.clearCache();
            throw new IllegalStateException("the compile with the terrain entries " + (on ? "on" : "off") + " threw " + e, e);
        } finally {
            TerrainSwitches.force(null);
        }
    }

    /** Non-empty sections of loaded chunks (with their neighbours loaded), nearest the camera first. */
    private static List<SectionPos> pick(ClientLevel level, Vec3 camera, int max) {
        int cx = SectionPos.blockToSectionCoord(camera.x), cy = SectionPos.blockToSectionCoord(camera.y), cz = SectionPos.blockToSectionCoord(camera.z);
        List<SectionPos> found = new ArrayList<>();
        for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, false);
                if (chunk == null || !neighboursLoaded(level, cx + dx, cz + dz)) continue;
                for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                    int y = cy + dy;
                    if (y < level.getMinSectionY() || y > level.getMaxSectionY()) continue;
                    if (chunk.getSection(level.getSectionIndexFromSectionY(y)).hasOnlyAir()) continue;
                    found.add(SectionPos.of(cx + dx, y, cz + dz));
                }
            }
        }
        found.sort(Comparator.comparingDouble(s -> s.center().distToCenterSqr(camera)));
        return found.size() > max ? new ArrayList<>(found.subList(0, Math.max(0, max))) : found;
    }

    private static boolean neighboursLoaded(ClientLevel level, int x, int z) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (level.getChunkSource().getChunk(x + dx, z + dz, false) == null) return false;
            }
        }
        return true;
    }

    /** The first difference between two compiles of a section, or null. */
    @Nullable
    static String compare(SectionCompiler.Results off, SectionCompiler.Results on) {
        if (!off.renderedLayers.keySet().equals(on.renderedLayers.keySet())) {
            return "layers " + off.renderedLayers.keySet() + " vs " + on.renderedLayers.keySet();
        }
        for (ChunkSectionLayer layer : off.renderedLayers.keySet()) {
            MeshData a = off.renderedLayers.get(layer), b = on.renderedLayers.get(layer);
            if (!a.drawState().equals(b.drawState())) return layer + ": draw state " + a.drawState() + " vs " + b.drawState();
            FaceGroups.take(b);
            int at = mismatch(a.vertexBuffer(), b.vertexBuffer(), a.drawState().format());
            if (at >= 0) return layer + ": " + vertexByte(at, a.drawState().format().getVertexSize());
            ByteBuffer ia = a.indexBuffer(), ib = b.indexBuffer();
            if ((ia == null) != (ib == null)) return layer + ": index buffer only " + (ia == null ? "on" : "off");
            if (ia != null && (at = ia.mismatch(ib)) >= 0) return layer + ": index byte " + at;
        }
        if ((off.transparencyState == null) != (on.transparencyState == null)) return "sort state only " + (off.transparencyState == null ? "on" : "off");
        if (off.transparencyState != null) {
            if (off.transparencyState.indexType() != on.transparencyState.indexType()) return "sort index type";
            Vector3f[] a = off.transparencyState.centroids(), b = on.transparencyState.centroids();
            if (a.length != b.length) return "sort centroids " + a.length + " vs " + b.length;
            for (int i = 0; i < a.length; i++) if (!a[i].equals(b[i])) return "sort centroid " + i + ": " + a[i] + " vs " + b[i];
        }
        if (off.blockEntities.size() != on.blockEntities.size()) return "block entities " + off.blockEntities.size() + " vs " + on.blockEntities.size();
        for (int i = 0; i < off.blockEntities.size(); i++) {
            if (off.blockEntities.get(i) != on.blockEntities.get(i)) return "block entity " + i + ": " + off.blockEntities.get(i) + " vs " + on.blockEntities.get(i);
        }
        for (Direction from : Direction.values()) {
            for (Direction to : Direction.values()) {
                if (off.visibilitySet.visibilityBetween(from, to) != on.visibilitySet.visibilityBetween(from, to)) return "visibility " + from + " -> " + to;
            }
        }
        return null;
    }

    /**
     * The first differing byte of two vertex buffers that the vertex format uses, or -1. Padding bytes (the block format's
     * one after the normal) are never written by vanilla, hold whatever the buffer had before and are never read by the
     * GPU, so they are not compared.
     */
    private static int mismatch(ByteBuffer a, ByteBuffer b, com.mojang.blaze3d.vertex.VertexFormat format) {
        if (a.remaining() != b.remaining()) return Math.min(a.remaining(), b.remaining());
        int size = format.getVertexSize();
        boolean[] used = new boolean[size];
        for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
            int offset = format.getOffset(element);
            for (int i = 0; i < element.byteSize(); i++) used[offset + i] = true;
        }
        for (int i = 0, n = a.remaining(); i < n; i++) {
            if (used[i % size] && a.get(a.position() + i) != b.get(b.position() + i)) return i;
        }
        return -1;
    }

    /** Where a differing byte lies in the block vertex layout (position, colour, uv, light, normal). */
    private static String vertexByte(int at, int vertexSize) {
        int offset = at % vertexSize;
        String element = offset < 12 ? "position" : offset < 16 ? "colour" : offset < 24 ? "uv" : offset < 28 ? "light" : "normal";
        return "vertex " + at / vertexSize + " byte " + offset + " (" + element + ")";
    }
}
