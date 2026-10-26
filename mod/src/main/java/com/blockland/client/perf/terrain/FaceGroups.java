package com.blockland.client.perf.terrain;

import com.blockland.client.render.PlantWind;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@code FACE_GROUPS} (rendering spec §20): each quad of a mesh in one of seven groups by the way it faces — six for quads
 * lying in an axis plane whose two triangles both wind towards that axis direction, one for the rest. A group of axis
 * quads can only be front-facing from one side of its planes, so when the camera is behind every one of them (by a safety
 * margin) the whole group would be culled by the GPU's back-face test anyway. The mesh is never reordered: the quads that
 * may face the camera are drawn through an index list in the mesh's own order ({@link FaceIndex}), so every fragment that
 * reaches the screen is drawn in the same order as before — depth ties resolve the same. Only for layers drawn with
 * back-face culling and without blending (solid, cutout); the translucent layers keep vanilla's sort.
 * <p>
 * Group 0 holds the quads of no axis direction — and every quad the terrain shader may move ({@code client/render/PlantWind}:
 * a quad bent by the wind can turn its face towards a camera far off to the side) — groups 1–6 the directions in
 * {@code Direction} order (down, up, north, south, west, east). Groups made before the wind's textures changed are not
 * used ({@link #current}). Positions are read as the first three floats of each vertex, in whatever space the mesh uses; the
 * camera handed to {@link #visible} is in the same space.
 */
public final class FaceGroups {
    public static final int GROUPS = 7, OTHER = 0, DOWN = 1, UP = 2, NORTH = 3, SOUTH = 4, WEST = 5, EAST = 6;
    /** Blocks the camera may stand behind a group's planes and still see it (bobbing moves the eye a little). */
    public static final double MARGIN = 1.0;
    /** The block format's texture uvs (two floats after the position and the colour). */
    private static final int UV = 16;

    /** Groups computed for a section mesh on a worker, until its upload attaches them to the section's buffers. */
    private static final Map<MeshData, FaceGroups> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** Each quad's group, in the mesh's order. */
    public final byte[] group;
    /** Quads per group; the lowest and highest plane coordinate of each axis group. */
    public final int[] count = new int[GROUPS];
    public final float[] low = new float[GROUPS], high = new float[GROUPS];
    public final int quads;
    /** {@code PlantWind.version()} when the quads were looked at. */
    private final int windVersion;

    private FaceGroups(int quads, int windVersion) {
        this.quads = quads;
        this.group = new byte[quads];
        this.windVersion = windVersion;
    }

    /** Whether the groups still hold (the moving textures are the ones they were made with); if not, draw the mesh whole. */
    public boolean current() {
        return windVersion == PlantWind.version();
    }

    /** The layers whose quads are grouped (back-face culled, no blending, no sort of their own). */
    public static boolean grouped(ChunkSectionLayer layer) {
        return layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT_MIPPED || layer == ChunkSectionLayer.CUTOUT;
    }

    /**
     * The groups of the quads of a vertex buffer of {@code QUADS} ({@code stride} bytes a vertex, from its position to its
     * limit; not changed). Null when there is nothing to cull (no quad in an axis group).
     */
    @Nullable
    public static FaceGroups of(ByteBuffer vertices, int stride) {
        int quadBytes = stride * 4;
        int quads = vertices.remaining() / quadBytes;
        if (quads == 0) return null;
        long base = MemoryUtil.memAddress(vertices);
        FaceGroups groups = new FaceGroups(quads, PlantWind.version());
        Arrays.fill(groups.low, Float.POSITIVE_INFINITY);
        Arrays.fill(groups.high, Float.NEGATIVE_INFINITY);
        for (int q = 0; q < quads; q++) {
            long at = base + (long) q * quadBytes;
            int g = classify(at, stride);
            groups.group[q] = (byte) g;
            groups.count[g]++;
            if (g != OTHER) {
                float plane = MemoryUtil.memGetFloat(at + 4L * axis(g));
                if (plane < groups.low[g]) groups.low[g] = plane;
                if (plane > groups.high[g]) groups.high[g] = plane;
            }
        }
        return groups.count[OTHER] == quads ? null : groups;
    }

    /** 0 = x, 1 = y, 2 = z: the coordinate an axis group's planes lie at. */
    private static int axis(int group) {
        return switch (group) {
            case WEST, EAST -> 0;
            case DOWN, UP -> 1;
            default -> 2;
        };
    }

    /**
     * The quad's group: its four corners in one axis plane and both triangles (0 1 2, 2 3 0) wound the same way, and no
     * corner on a texture the wind moves.
     */
    private static int classify(long quad, int stride) {
        for (int v = 0; v < 4; v++) {
            long uv = quad + (long) v * stride + UV;
            if (PlantWind.moves(MemoryUtil.memGetFloat(uv), MemoryUtil.memGetFloat(uv + 4))) return OTHER;
        }
        float x0 = MemoryUtil.memGetFloat(quad), y0 = MemoryUtil.memGetFloat(quad + 4), z0 = MemoryUtil.memGetFloat(quad + 8);
        float x1 = MemoryUtil.memGetFloat(quad + stride), y1 = MemoryUtil.memGetFloat(quad + stride + 4), z1 = MemoryUtil.memGetFloat(quad + stride + 8);
        long v2 = quad + 2L * stride, v3 = quad + 3L * stride;
        float x2 = MemoryUtil.memGetFloat(v2), y2 = MemoryUtil.memGetFloat(v2 + 4), z2 = MemoryUtil.memGetFloat(v2 + 8);
        float x3 = MemoryUtil.memGetFloat(v3), y3 = MemoryUtil.memGetFloat(v3 + 4), z3 = MemoryUtil.memGetFloat(v3 + 8);
        if (x0 == x1 && x0 == x2 && x0 == x3) {
            int a = sign((double) (y1 - y0) * (z2 - z0) - (double) (z1 - z0) * (y2 - y0));
            int b = sign((double) (y3 - y2) * (z0 - z2) - (double) (z3 - z2) * (y0 - y2));
            return a == b && a != 0 ? (a > 0 ? EAST : WEST) : OTHER;
        }
        if (y0 == y1 && y0 == y2 && y0 == y3) {
            int a = sign((double) (z1 - z0) * (x2 - x0) - (double) (x1 - x0) * (z2 - z0));
            int b = sign((double) (z3 - z2) * (x0 - x2) - (double) (x3 - x2) * (z0 - z2));
            return a == b && a != 0 ? (a > 0 ? UP : DOWN) : OTHER;
        }
        if (z0 == z1 && z0 == z2 && z0 == z3) {
            int a = sign((double) (x1 - x0) * (y2 - y0) - (double) (y1 - y0) * (x2 - x0));
            int b = sign((double) (x3 - x2) * (y0 - y2) - (double) (y3 - y2) * (x0 - x2));
            return a == b && a != 0 ? (a > 0 ? SOUTH : NORTH) : OTHER;
        }
        return OTHER;
    }

    private static int sign(double v) {
        return v > 0 ? 1 : v < 0 ? -1 : 0;
    }

    /**
     * The groups that may face a camera at ({@code x, y, z}) in the mesh's space (bit per group): a quad facing +axis at
     * plane c is front-facing only from beyond c, one facing −axis only from before it.
     */
    public int visible(double x, double y, double z) {
        return visible(x, y, z, MARGIN);
    }

    /** {@link #visible} with a wider margin (for a camera that may move a little before it is asked again). */
    public int visible(double x, double y, double z, double margin) {
        int mask = 0;
        for (int g = 0; g < GROUPS; g++) {
            if (count[g] == 0) continue;
            boolean seen = switch (g) {
                case DOWN -> y < high[g] + margin;
                case UP -> y > low[g] - margin;
                case NORTH -> z < high[g] + margin;
                case SOUTH -> z > low[g] - margin;
                case WEST -> x < high[g] + margin;
                case EAST -> x > low[g] - margin;
                default -> true;
            };
            if (seen) mask |= 1 << g;
        }
        return mask;
    }

    /** Whether the mask holds every group with quads (the whole mesh). */
    public boolean all(int mask) {
        for (int g = 0; g < GROUPS; g++) if (count[g] != 0 && (mask & 1 << g) == 0) return false;
        return true;
    }

    /** How many quads the groups in the mask hold. */
    public int quads(int mask) {
        int n = 0;
        for (int g = 0; g < GROUPS; g++) if ((mask & 1 << g) != 0) n += count[g];
        return n;
    }

    /**
     * Appends the indices of the quads in the mask, in the mesh's order, as vanilla's quad index list has them (per quad
     * {@code 0 1 2 2 3 0} from its first vertex), the mesh's first quad being {@code baseQuad}: shorts, or ints when
     * {@code wide}.
     */
    public void writeIndices(ByteBuffer out, int mask, int baseQuad, boolean wide) {
        for (int q = 0; q < quads; q++) {
            if ((mask & 1 << group[q]) == 0) continue;
            int v = (baseQuad + q) * 4;
            if (wide) {
                out.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
            } else {
                out.putShort((short) v).putShort((short) (v + 1)).putShort((short) (v + 2)).putShort((short) (v + 2))
                        .putShort((short) (v + 3)).putShort((short) v);
            }
        }
    }

    // ---- vanilla's section meshes ------------------------------------------------------------------------------------

    /** A compile's mesh was grouped (worker): kept until the mesh is uploaded. */
    static void pending(MeshData mesh, FaceGroups groups) {
        PENDING.put(mesh, groups);
    }

    /** The groups of a mesh being uploaded (render thread), or null; forgotten here. */
    @Nullable
    public static FaceGroups take(MeshData mesh) {
        return PENDING.remove(mesh);
    }

    /** {@code SectionBuffers}' groups (attached at upload) and the index list of the quads last drawn. */
    public interface Holder {
        @Nullable
        FaceGroups blockland$faceGroups();

        /** Attaches the groups of a new mesh (the old index list goes). */
        void blockland$setFaceGroups(@Nullable FaceGroups groups);

        FaceIndex blockland$faceIndex();
    }
}
