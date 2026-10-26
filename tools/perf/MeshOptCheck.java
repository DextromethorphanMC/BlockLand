package com.blockland.client.graphics.far;

import com.blockland.client.perf.terrain.FaceGroups;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.core.Direction;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Random;

/** Offline checks of FACE_GROUPS (classification against vanilla's own face vertex order) and FAR_HIDDEN_GEOMETRY's flood. */
public final class MeshOptCheck {
    static int failures, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static final int STRIDE = 32;

    /** A cube face as FaceBakery winds it (FaceInfo order), from..to, tagged in the colour bytes. */
    static void face(ByteBuffer buf, Direction d, float x0, float y0, float z0, float x1, float y1, float z1, int tag) {
        FaceInfo info = FaceInfo.fromFacing(d);
        float[] c = new float[6];
        c[FaceInfo.Constants.MIN_X] = x0;
        c[FaceInfo.Constants.MIN_Y] = y0;
        c[FaceInfo.Constants.MIN_Z] = z0;
        c[FaceInfo.Constants.MAX_X] = x1;
        c[FaceInfo.Constants.MAX_Y] = y1;
        c[FaceInfo.Constants.MAX_Z] = z1;
        for (int v = 0; v < 4; v++) {
            FaceInfo.VertexInfo vi = info.getVertexInfo(v);
            int at = buf.position();
            buf.putFloat(c[vi.xFace]).putFloat(c[vi.yFace]).putFloat(c[vi.zFace]);
            buf.putInt(tag);
            for (int k = 16; k < STRIDE; k++) buf.put((byte) 0);
            buf.position(at + STRIDE);
        }
    }

    static int group(Direction d) {
        return 1 + d.ordinal();
    }

    public static void main(String[] args) {
        // 1. every face of a cube lands in its own direction's group; a crossed plant quad in the other group
        Random random = new Random(7);
        int quads = 400;
        ByteBuffer buf = MemoryUtil.memAlloc(quads * 4 * STRIDE);
        Direction[] dirs = new Direction[quads];
        for (int q = 0; q < quads; q++) {
            if (q % 7 == 6) {
                // a diagonal quad (cross plant)
                int at = buf.position();
                float[][] p = {{0, 0, 0}, {1, 0, 1}, {1, 1, 1}, {0, 1, 0}};
                for (float[] v : p) {
                    int vat = buf.position();
                    buf.putFloat(v[0] + q).putFloat(v[1]).putFloat(v[2]).putInt(q);
                    buf.position(vat + STRIDE);
                }
                buf.position(at + 4 * STRIDE);
                dirs[q] = null;
                continue;
            }
            Direction d = Direction.values()[random.nextInt(6)];
            float x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
            face(buf, d, x, y, z, x + 1, y + 1, z + 1, q);
            dirs[q] = d;
        }
        buf.flip();
        ByteBuffer before = MemoryUtil.memAlloc(buf.remaining());
        MemoryUtil.memCopy(buf, before);
        FaceGroups g = FaceGroups.of(buf, STRIDE);
        check(g != null, "groups made");
        check(buf.equals(before), "the mesh is not changed");
        int[] expected = new int[7];
        for (Direction d : dirs) expected[d == null ? 0 : group(d)]++;
        for (int k = 0; k < 7; k++) check(g.count[k] == expected[k], "group " + k + " count " + g.count[k] + " vs " + expected[k]);
        for (int q = 0; q < quads; q++) check(g.group[q] == (dirs[q] == null ? 0 : group(dirs[q])), "quad " + q + " faces " + dirs[q]);
        // the index list: the quads of the mask in the mesh's order, each as vanilla's 0 1 2 2 3 0 from its first vertex
        int mask = 1 << FaceGroups.UP | 1 << FaceGroups.OTHER | 1 << FaceGroups.EAST;
        for (boolean wide : new boolean[]{false, true}) {
            ByteBuffer idx = MemoryUtil.memAlloc(g.quads(mask) * 6 * (wide ? 4 : 2));
            g.writeIndices(idx, mask, 3, wide);
            check(idx.position() == idx.capacity(), "index list fills its count");
            idx.flip();
            int at = 0;
            for (int q = 0; q < quads; q++) {
                if ((mask & 1 << g.group[q]) == 0) continue;
                int v = (q + 3) * 4;
                int[] want = {v, v + 1, v + 2, v + 2, v + 3, v};
                for (int w : want) {
                    int got = wide ? idx.getInt(at * 4) : Short.toUnsignedInt(idx.getShort(at * 2));
                    check(got == w, "index " + at + " = " + got + ", wanted " + w);
                    at++;
                }
            }
            MemoryUtil.memFree(idx);
        }
        MemoryUtil.memFree(before);
        // 2. visibility: an up face at y = 1 (cube 0..1) is seen from above, not from below; a down face the other way
        ByteBuffer one = MemoryUtil.memAlloc(2 * 4 * STRIDE);
        face(one, Direction.UP, 0, 0, 0, 1, 1, 1, 0);
        face(one, Direction.EAST, 0, 0, 0, 1, 1, 1, 1);
        one.flip();
        FaceGroups h = FaceGroups.of(one, STRIDE);
        check((h.visible(0.5, 5, 0.5) & 1 << FaceGroups.UP) != 0, "up seen from above");
        check((h.visible(0.5, -5, 0.5) & 1 << FaceGroups.UP) == 0, "up hidden from below");
        check((h.visible(0.5, 0.5, 0.5) & 1 << FaceGroups.UP) != 0, "up kept within the margin");
        check((h.visible(5, 0.5, 0.5) & 1 << FaceGroups.EAST) != 0, "east seen from +x");
        check((h.visible(-5, 0.5, 0.5) & 1 << FaceGroups.EAST) == 0, "east hidden from -x");
        // 3. the real winding test: GL's front face is counter-clockwise on screen; a camera on the face's side must see the
        // face's triangles counter-clockwise when looking at them (viewed from +normal, right-handed)
        for (Direction d : Direction.values()) {
            ByteBuffer f = MemoryUtil.memAlloc(4 * STRIDE);
            face(f, d, 0, 0, 0, 1, 1, 1, 0);
            f.flip();
            long a = MemoryUtil.memAddress(f);
            double[][] v = new double[4][3];
            for (int i = 0; i < 4; i++) for (int k = 0; k < 3; k++) v[i][k] = MemoryUtil.memGetFloat(a + (long) i * STRIDE + 4L * k);
            double[] e1 = sub(v[1], v[0]), e2 = sub(v[2], v[0]);
            double[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
            double dot = n[0] * d.getStepX() + n[1] * d.getStepY() + n[2] * d.getStepZ();
            check(dot > 0, d + ": vanilla winds its face counter-clockwise seen from outside (normal along the direction)");
            FaceGroups single = FaceGroups.of(f, STRIDE);
            check(single != null && single.count[group(d)] == 1, d + " classified as its own direction");
            MemoryUtil.memFree(f);
        }
        MemoryUtil.memFree(buf);
        MemoryUtil.memFree(one);

        // 4. the wind: a quad on a texture the wind moves (its uvs in a moving cell) is never culled
        com.blockland.client.render.PlantWind.publish(java.util.Map.of(), new boolean[]{true, false, false, false}, 2, 2);
        for (float uvAt : new float[]{0.1f, 0.7f}) {
            ByteBuffer w = MemoryUtil.memAlloc(4 * STRIDE);
            face(w, Direction.UP, 0, 0, 0, 1, 1, 1, 0);
            w.flip();
            long wa = MemoryUtil.memAddress(w);
            for (int v = 0; v < 4; v++) {
                MemoryUtil.memPutFloat(wa + (long) v * STRIDE + 16, uvAt);
                MemoryUtil.memPutFloat(wa + (long) v * STRIDE + 20, uvAt);
            }
            FaceGroups wg = FaceGroups.of(w, STRIDE);
            if (uvAt < 0.5f) check(wg == null, "a moving texture's quad stays in the drawn-always group");
            else check(wg != null && wg.count[FaceGroups.UP] == 1 && wg.current(), "a still texture's quad is grouped");
            MemoryUtil.memFree(w);
        }
        FaceGroups stale = FaceGroups.of(one(), STRIDE);
        com.blockland.client.render.PlantWind.publish(java.util.Map.of(), new boolean[0], 0, 0);
        check(stale != null && !stale.current(), "groups made before the wind's textures changed are not used");

        // 5. hidden cells: a sealed air pocket inside stone is hidden with its walls; a cave open to the side is not
        int height = 32, cells = height * 256;
        long[] open = new long[(cells + 63) >> 6];
        for (int c = 0; c < cells; c++) if ((c >> 8) >= 24) open[c >> 6] |= 1L << c; // air above y 24
        int pocket = idx(8, 10, 8), cave = idx(5, 12, 0), cave2 = idx(5, 12, 1), cave3 = idx(5, 12, 2);
        set(open, pocket);
        set(open, cave);
        set(open, cave2);
        set(open, cave3);
        long[] hidden = HiddenCells.fromOpen(open, height);
        check(HiddenCells.has(hidden, pocket), "sealed pocket hidden");
        check(HiddenCells.has(hidden, idx(8, 11, 8)), "pocket ceiling (stone, only next to the pocket) hidden");
        check(HiddenCells.has(hidden, idx(8, 5, 8)), "deep stone hidden");
        check(!HiddenCells.has(hidden, cave3), "cave reaching the side visible");
        check(!HiddenCells.has(hidden, idx(5, 12, 3)), "stone at the cave's end visible");
        check(!HiddenCells.has(hidden, idx(8, 23, 8)), "surface block visible");
        check(HiddenCells.has(hidden, idx(8, 22, 8)), "block under the surface hidden");
        check(!HiddenCells.has(hidden, idx(0, 10, 8)), "border cells never hidden");
        check(!HiddenCells.has(hidden, idx(8, 0, 8)), "bottom cells never hidden");

        System.out.println(checks - failures + " / " + checks + " checks passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static ByteBuffer one() {
        ByteBuffer f = MemoryUtil.memAlloc(4 * STRIDE);
        face(f, Direction.EAST, 0, 0, 0, 1, 1, 1, 0);
        f.flip();
        // uvs on a still texture
        for (int v = 0; v < 4; v++) {
            MemoryUtil.memPutFloat(MemoryUtil.memAddress(f) + (long) v * STRIDE + 16, 0.7f);
            MemoryUtil.memPutFloat(MemoryUtil.memAddress(f) + (long) v * STRIDE + 20, 0.7f);
        }
        return f;
    }

    static int idx(int x, int y, int z) {
        return (y * 16 + z) * 16 + x;
    }

    static void set(long[] bits, int c) {
        bits[c >> 6] |= 1L << c;
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }
}
