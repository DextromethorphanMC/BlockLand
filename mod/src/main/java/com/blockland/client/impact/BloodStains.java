package com.blockland.client.impact;

import com.blockland.client.perf.Optimization;
import com.blockland.client.render.BlockFaces;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The vertices of the blood lying on things (rendering spec §19), for {@code blockland:core/impact_blood}: a stain on a face
 * is its quad cut to each rectangle of the surface it lies on ({@link ImpactParticles.Debris#clip}), every corner telling
 * the shader how far the surface's nearest real edges are along the face's two axes (a distance to a line is linear, so
 * it interpolates exactly over a piece) — the outline turns to follow the edge; a run down a wall likewise; a stain on a
 * plant lies on the plant's quad with the block atlas uv of the plant under it (its alpha cuts the stain to the blades).
 * Stains on what the wind moves carry its bits and the point it moves them with. Render thread only.
 */
final class BloodStains {
    static final VertexFormatElement STAIN = element(4), SHAPE = element(4), ANCHOR = element(4), ATLAS = element(2);
    /** Position, colour, the stain's uv, the lightmap, then the four elements above (see impact_blood.vsh). */
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV2", VertexFormatElement.UV2)
            .add("Stain", STAIN)
            .add("Shape", SHAPE)
            .add("Anchor", ANCHOR)
            .add("Atlas", ATLAS)
            .build();
    /** Stains are not drawn beyond this many blocks (they are small; the pool keeps them alive). */
    private static final float DISTANCE = 72;
    /** A distance to an edge that means "no edge" (blocks). */
    private static final float NO_EDGE = 8;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static ByteBuffer bytes = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
    private static int vertices;

    // the stain being written (per stain, the same at every corner)
    private static float seed, wet, radius, gloss, code, wind, ax, ay, az;
    private static int argb, light;
    private static boolean anchorAtVertex;
    /** A stain's quad and its pieces while cut: per corner s, t on its face (from the stain) and its uv. */
    private static final float[] quad = new float[16], cut = new float[4 * 12], cutSwap = new float[4 * 12];
    /** Which of the two holds the piece {@link #cutPiece} made last. */
    private static float[] piece = cut;

    /**
     * A stain quad's pieces as last cut ({@code IMPACT_POOLS}) and what they were cut from: the quad's corners (bits) and
     * the stain's {@code clipStamp}. One per stain and quad (the splat, its run), kept with the pool's piece.
     */
    static final class Cut {
        private final int[] quadBits = new int[16];
        private int stamp;
        private boolean valid;
        /** Rectangles cut (the stain's {@code clips} then) and each one's piece's corner count (below 3: nothing). */
        private int rects;
        private final byte[] n = new byte[ImpactParticles.MAX_CLIPS];
        /** The pieces' corners one after another (s, t, u, v each). */
        private float[] corners = new float[48];

        /** Whether this was cut from this very quad and these very rectangles. */
        boolean holds(float[] quad, ImpactParticles.Debris p) {
            if (!valid || stamp != p.clipStamp || rects != p.clips) return false;
            for (int i = 0; i < 16; i++) if (quadBits[i] != Float.floatToRawIntBits(quad[i])) return false;
            return true;
        }

        void from(float[] quad, ImpactParticles.Debris p) {
            valid = true;
            stamp = p.clipStamp;
            rects = p.clips;
            for (int i = 0; i < 16; i++) quadBits[i] = Float.floatToRawIntBits(quad[i]);
        }
    }

    private BloodStains() {
    }

    /** A vertex element of our own: the first free id from the top (Minecraft's take the lowest ones). */
    private static VertexFormatElement element(int floats) {
        for (int id = VertexFormatElement.MAX_COUNT - 1; id > 5; id--) {
            if (VertexFormatElement.byId(id) == null) {
                return VertexFormatElement.register(id, 0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, floats);
            }
        }
        throw new IllegalStateException("No free vertex format element id for the blood stains");
    }

    /**
     * Writes every stain among the first {@code count} of {@code pieces} (the pool, or its live pieces in pool order);
     * returns the quads written ({@link #data()} holds them).
     */
    static int build(ImpactParticles.Debris[] pieces, int count, Vec3 cam, float partialTick) {
        bytes.clear();
        vertices = 0;
        int quads = 0;
        for (int k = 0; k < count; k++) {
            ImpactParticles.Debris p = pieces[k];
            if (!p.alive || p.fluid != ImpactParticles.BLOOD || !p.resting) continue;
            if (p.kind == ImpactParticles.PLANT) quads += plant(p, cam, partialTick);
            else if (p.face >= 0) quads += face(p, cam, partialTick);
        }
        bytes.flip();
        return quads;
    }

    /** The vertices written by the last {@link #build}. */
    static ByteBuffer data() {
        return bytes;
    }

    /** Colour, wetness and gloss of a stain now: it darkens as it dries, pales and thins as rain washes it out. */
    private static boolean look(ImpactParticles.Debris p, float pt) {
        float fade = ImpactParticles.fade(p.age, p.life, pt, 0.2f);
        if (fade <= 0.01f) return false;
        wet = 1 - Mth.clamp((p.age - p.landed + pt) / Math.max(1, p.dry), 0, 1);
        int rgb = ImpactSpawner.mix(ImpactSpawner.darken(p.rgb, 0.55f), p.rgb, wet);
        float alpha = fade;
        radius = p.splat;
        if (p.washed) {
            rgb = ImpactSpawner.mix(rgb, 0xB85A5A, 0.3f);
            alpha *= 0.75f;
            radius *= 1.2f;
        }
        argb = Math.round(alpha * 255) << 24 | rgb;
        seed = (p.size * 977 + p.landed * 0.137f) % 1;
        gloss = Math.max(wet, p.washed ? 0.6f : 0);
        light = p.light;
        wind = p.windBits;
        return true;
    }

    // ---- on a face ----------------------------------------------------------------------------------

    private static int face(ImpactParticles.Debris p, Vec3 cam, float pt) {
        Direction face = DIRECTIONS[p.face];
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        float x = (float) (p.x - cam.x), y = (float) (p.y - cam.y), z = (float) (p.z - cam.z);
        // far away, or seen from behind the face it lies on
        float distanceSqr = x * x + y * y + z * z;
        if (distanceSqr > DISTANCE * DISTANCE || x * nx + y * ny + z * nz > 0) return 0;
        if (!look(p, pt)) return 0;
        // off the face by more the farther it is (the depth buffer's precision falls with distance)
        float lift = 0.0025f + 0.00007f * Mth.sqrt(distanceSqr);
        x += nx * lift;
        y += ny * lift;
        z += nz * lift;
        float r = radius;
        int kind = p.kind == ImpactParticles.POOL ? 1 : 0;
        code = kind * 16 + p.face * 2;
        ax = x;
        ay = y;
        az = z;
        // a leaf block rustles the stain on it vertex by vertex (its field is smooth: the stain stays whole)
        anchorAtVertex = true;
        // the long axis on the face, and the one across it
        float ux = p.sx, uy = p.sy, uz = p.sz;
        float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        // the quad reaches past the outline for a splat's satellites (the shader's uv runs to 1.35)
        float along = r * p.stretch * 1.35f, across = r * 1.35f;
        int sa = BlockFaces.sAxis(face), ta = BlockFaces.tAxis(face);
        corner(0, -ux * along - vx * across, -uy * along - vy * across, -uz * along - vz * across, sa, ta, -1.35f, -1.35f);
        corner(1, ux * along - vx * across, uy * along - vy * across, uz * along - vz * across, sa, ta, 1.35f, -1.35f);
        corner(2, ux * along + vx * across, uy * along + vy * across, uz * along + vz * across, sa, ta, 1.35f, 1.35f);
        corner(3, -ux * along + vx * across, -uy * along + vy * across, -uz * along + vz * across, sa, ta, -1.35f, 1.35f);
        int quads = clipped(p, false, x, y, z, sa, ta);
        if (p.run < 0.004f || ny != 0) return quads;
        // its run down the wall: from the stain's middle to the tip, narrowing
        float run = p.run + (p.run < p.runMax ? (p.runMax - p.run) * 0.05f * pt : 0);
        float w = Math.max(0.006f, r * 0.45f);
        radius = w;
        code = kind * 16 + p.face * 2 + 1;
        // across the run: along the wall, level
        float hx = -nz, hz = nx;
        corner(0, -hx * w, 0, -hz * w, sa, ta, -1, 0);
        corner(1, hx * w, 0, hz * w, sa, ta, 1, 0);
        corner(2, hx * w, -run, hz * w, sa, ta, 1, 1);
        corner(3, -hx * w, -run, -hz * w, sa, ta, -1, 1);
        return quads + clipped(p, true, x, y, z, sa, ta);
    }

    private static void corner(int i, float dx, float dy, float dz, int sa, int ta, float u, float v) {
        quad[i * 4] = sa == 0 ? dx : sa == 1 ? dy : dz;
        quad[i * 4 + 1] = ta == 1 ? dy : dz;
        quad[i * 4 + 2] = u;
        quad[i * 4 + 3] = v;
    }

    /**
     * Writes {@link #quad} cut to each rectangle of the surface the stain lies on (so it never hangs over an edge into the
     * air, and goes on over a neighbouring block that carries the surface on), each piece a fan of quads, every corner with
     * its distances to the rectangle's real edges nearest the stain; returns how many quads. {@code run}: the quad is the
     * stain's run down the wall (it is cut on its own).
     */
    private static int clipped(ImpactParticles.Debris p, boolean run, float x, float y, float z, int sa, int ta) {
        if (Optimization.IMPACT_POOLS.on()) return kept(p, run, x, y, z, sa, ta);
        int quads = 0;
        for (int r = 0; r < p.clips; r++) {
            int n = cutPiece(p, r);
            if (n < 3) continue;
            quads += fan(piece, 0, n, p, r, x, y, z, sa, ta);
        }
        return quads;
    }

    /**
     * {@link #clipped} with the cut kept ({@code IMPACT_POOLS}): the pieces depend only on the quad's corners and the
     * stain's rectangles, and the rectangles change only where {@code clip} works them out again ({@code clipStamp}); while
     * both are the same (the corners compared bit for bit) the kept pieces are the very floats a new cut would give. The
     * fans are written from them as before, at the stain's position now.
     */
    private static int kept(ImpactParticles.Debris p, boolean run, float x, float y, float z, int sa, int ta) {
        Cut kept = run ? p.runCut : p.splatCut;
        if (kept == null) {
            kept = new Cut();
            if (run) p.runCut = kept;
            else p.splatCut = kept;
        }
        if (!kept.holds(quad, p)) {
            kept.from(quad, p);
            int at = 0;
            for (int r = 0; r < p.clips; r++) {
                int n = cutPiece(p, r);
                kept.n[r] = (byte) n;
                if (n < 3) continue;
                if (at + n * 4 > kept.corners.length) kept.corners = Arrays.copyOf(kept.corners, Math.max(kept.corners.length * 2, at + n * 4));
                System.arraycopy(piece, 0, kept.corners, at, n * 4);
                at += n * 4;
            }
        }
        int quads = 0, at = 0;
        for (int r = 0; r < kept.rects; r++) {
            int n = kept.n[r];
            if (n < 3) continue;
            quads += fan(kept.corners, at, n, p, r, x, y, z, sa, ta);
            at += n * 4;
        }
        return quads;
    }

    /** {@link #quad} cut to the stain's rectangle {@code r}: returns the piece's corner count; the piece is {@link #piece}. */
    private static int cutPiece(ImpactParticles.Debris p, int r) {
        float[] in = cut, out = cutSwap;
        System.arraycopy(quad, 0, in, 0, 16);
        int n = 4;
        for (int edge = 0; edge < 4 && n >= 3; edge++) {
            // keep s ≥ s0, t ≥ t0, s ≤ s1, t ≤ t1
            int k = edge & 1;
            float limit = p.clip[r * 4 + edge], sign = edge < 2 ? 1 : -1;
            int m = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float di = (in[i * 4 + k] - limit) * sign, dj = (in[j * 4 + k] - limit) * sign;
                if (di >= 0) {
                    System.arraycopy(in, i * 4, out, m * 4, 4);
                    m++;
                }
                if (di >= 0 != dj >= 0 && m < 12) {
                    float f = di / (di - dj);
                    for (int c = 0; c < 4; c++) out[m * 4 + c] = in[i * 4 + c] + (in[j * 4 + c] - in[i * 4 + c]) * f;
                    m++;
                }
                if (m >= 12) break;
            }
            float[] swap = in;
            in = out;
            out = swap;
            n = m;
        }
        piece = in;
        return n;
    }

    /** A cut piece ({@code n} corners from {@code base} in {@code poly}) on the stain's rectangle {@code r}, as a fan of quads. */
    private static int fan(float[] poly, int base, int n, ImpactParticles.Debris p, int r, float x, float y, float z, int sa, int ta) {
        // the real edges of this rectangle nearest the stain, one along each axis (or none)
        int edges = p.edges == null ? 0 : p.edges[r];
        int es = nearest(p.clip, r, edges, 0, 2), et = nearest(p.clip, r, edges, 1, 3);
        int quads = 0;
        // a fan from the first corner, two triangles a quad (the last one may be half empty)
        for (int i = 1; i + 1 < n; i += 2) {
            faceVertex(poly, base, 0, x, y, z, sa, ta, p.clip, r, es, et);
            faceVertex(poly, base, i, x, y, z, sa, ta, p.clip, r, es, et);
            faceVertex(poly, base, i + 1, x, y, z, sa, ta, p.clip, r, es, et);
            faceVertex(poly, base, Math.min(i + 2, n - 1), x, y, z, sa, ta, p.clip, r, es, et);
            quads++;
        }
        return quads;
    }

    /** Of the rectangle's edges {@code a} and {@code b} (s0 / s1, or t0 / t1), the real one nearest the stain, or -1. */
    private static int nearest(float[] clip, int r, int edges, int a, int b) {
        boolean ea = (edges >> a & 1) != 0, eb = (edges >> b & 1) != 0;
        if (ea && eb) return Math.abs(clip[r * 4 + a]) <= Math.abs(clip[r * 4 + b]) ? a : b;
        return ea ? a : eb ? b : -1;
    }

    private static void faceVertex(float[] poly, int base, int i, float x, float y, float z, int sa, int ta, float[] clip, int r,
                                   int es, int et) {
        float s = poly[base + i * 4], t = poly[base + i * 4 + 1];
        float edgeS = es < 0 ? NO_EDGE : es == 0 ? s - clip[r * 4] : clip[r * 4 + 2] - s;
        float edgeT = et < 0 ? NO_EDGE : et == 1 ? t - clip[r * 4 + 1] : clip[r * 4 + 3] - t;
        float px = x + (sa == 0 ? s : 0), py = y + (sa == 1 ? s : ta == 1 ? t : 0), pz = z + (ta == 2 ? t : 0);
        vertex(px, py, pz, poly[base + i * 4 + 2], poly[base + i * 4 + 3], edgeS, edgeT, anchorAtVertex ? px : ax, anchorAtVertex ? py : ay,
                anchorAtVertex ? pz : az, -1, -1);
    }

    // ---- on a plant ---------------------------------------------------------------------------------

    /** A stain on a plant's quad: a small splat, cut by the plant's texture, moved by the wind at its middle. */
    private static int plant(ImpactParticles.Debris p, Vec3 cam, float pt) {
        float x = (float) (p.x - cam.x), y = (float) (p.y - cam.y), z = (float) (p.z - cam.z);
        if (x * x + y * y + z * z > DISTANCE * DISTANCE || !look(p, pt)) return 0;
        // off the quad towards the eye
        float nx = p.paz, nz = -p.pax;
        if (nx * x + nz * z > 0) {
            nx = -nx;
            nz = -nz;
        }
        x += nx * 0.0025f;
        z += nz * 0.0025f;
        code = 2 * 16;
        ax = x;
        ay = y;
        az = z;
        anchorAtVertex = false;
        float r = radius * 1.35f;
        for (int i = 0; i < 4; i++) {
            float su = i == 1 || i == 2 ? 1 : -1, sv = i >= 2 ? 1 : -1;
            float ca = su * r, cu = sv * r;
            vertex(x + p.pax * ca, y + cu, z + p.paz * ca, su * 1.35f, sv * 1.35f, NO_EDGE, NO_EDGE, ax, ay, az,
                    p.atlasU + p.dudA * ca + p.dudU * cu, p.atlasV + p.dvdA * ca + p.dvdU * cu);
        }
        return 1;
    }

    // ---- writing ------------------------------------------------------------------------------------

    private static void vertex(float x, float y, float z, float u, float v, float edgeS, float edgeT, float anchorX, float anchorY,
                               float anchorZ, float atlasU, float atlasV) {
        int size = FORMAT.getVertexSize();
        if (bytes.remaining() < size) grow();
        // memAddress is already the address at the buffer's position (adding the position again wrote past the buffer)
        long at = MemoryUtil.memAddress(bytes);
        MemoryUtil.memPutFloat(at + FORMAT.getOffset(VertexFormatElement.POSITION), x);
        MemoryUtil.memPutFloat(at + FORMAT.getOffset(VertexFormatElement.POSITION) + 4, y);
        MemoryUtil.memPutFloat(at + FORMAT.getOffset(VertexFormatElement.POSITION) + 8, z);
        // ABGR in memory (r first)
        long c = at + FORMAT.getOffset(VertexFormatElement.COLOR);
        MemoryUtil.memPutByte(c, (byte) (argb >> 16));
        MemoryUtil.memPutByte(c + 1, (byte) (argb >> 8));
        MemoryUtil.memPutByte(c + 2, (byte) argb);
        MemoryUtil.memPutByte(c + 3, (byte) (argb >>> 24));
        MemoryUtil.memPutFloat(at + FORMAT.getOffset(VertexFormatElement.UV0), u);
        MemoryUtil.memPutFloat(at + FORMAT.getOffset(VertexFormatElement.UV0) + 4, v);
        MemoryUtil.memPutShort(at + FORMAT.getOffset(VertexFormatElement.UV2), (short) (light & 0xFFFF));
        MemoryUtil.memPutShort(at + FORMAT.getOffset(VertexFormatElement.UV2) + 2, (short) (light >> 16 & 0xFFFF));
        long st = at + FORMAT.getOffset(STAIN);
        MemoryUtil.memPutFloat(st, edgeS);
        MemoryUtil.memPutFloat(st + 4, edgeT);
        MemoryUtil.memPutFloat(st + 8, seed);
        MemoryUtil.memPutFloat(st + 12, wet);
        long sh = at + FORMAT.getOffset(SHAPE);
        MemoryUtil.memPutFloat(sh, code);
        MemoryUtil.memPutFloat(sh + 4, wind);
        MemoryUtil.memPutFloat(sh + 8, radius);
        MemoryUtil.memPutFloat(sh + 12, gloss);
        long an = at + FORMAT.getOffset(ANCHOR);
        MemoryUtil.memPutFloat(an, anchorX);
        MemoryUtil.memPutFloat(an + 4, anchorY);
        MemoryUtil.memPutFloat(an + 8, anchorZ);
        MemoryUtil.memPutFloat(an + 12, 0);
        long tx = at + FORMAT.getOffset(ATLAS);
        MemoryUtil.memPutFloat(tx, atlasU);
        MemoryUtil.memPutFloat(tx + 4, atlasV);
        bytes.position(bytes.position() + size);
        vertices++;
    }

    private static void grow() {
        ByteBuffer bigger = ByteBuffer.allocateDirect(bytes.capacity() * 2).order(ByteOrder.nativeOrder());
        bytes.flip();
        bigger.put(bytes);
        bytes = bigger;
    }
}
