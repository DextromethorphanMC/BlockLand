package com.blockland.client.decal;

import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * A decal quad laid on an axis-aligned face and clipped to that face's rectangle (Sutherland–Hodgman), so a decal
 * near an edge never hangs over into the air. Texture coordinates are clipped along with the corners.
 */
final class DecalGeometry {
    /** Clipped polygon: up to 8 corners, xyz and uv interleaved per corner. */
    record Polygon(float[] xyz, float[] uv, int count) {
    }

    private DecalGeometry() {
    }

    /**
     * @param centre    decal centre on the face plane
     * @param halfU     half extent along the cut (texture u)
     * @param halfV     half extent across it (texture v, up)
     * @param axis      the face normal's axis (0 = x, 1 = y, 2 = z)
     * @param rect      face rectangle on the other two axes: min s, min t, max s, max t (s/t = the remaining axes in order)
     * @param u0        texture u range of the chosen variant
     * @return null when nothing of the decal is left on the face
     */
    @Nullable
    static Polygon clip(Vector3f centre, Vector3f halfU, Vector3f halfV, int axis, float[] rect, float u0, float u1) {
        int sAxis = axis == 0 ? 1 : 0, tAxis = axis == 2 ? 1 : 2;
        // a face with the normal on X uses (y, z), on Y (x, z), on Z (x, y)
        float[] xyz = new float[8 * 3 * 2];
        float[] uv = new float[8 * 2 * 2];
        int n = 0;
        float[][] corners = {{-1, -1, u0, 1}, {1, -1, u1, 1}, {1, 1, u1, 0}, {-1, 1, u0, 0}};
        for (float[] c : corners) {
            xyz[n * 3] = centre.x + halfU.x * c[0] + halfV.x * c[1];
            xyz[n * 3 + 1] = centre.y + halfU.y * c[0] + halfV.y * c[1];
            xyz[n * 3 + 2] = centre.z + halfU.z * c[0] + halfV.z * c[1];
            uv[n * 2] = c[2];
            uv[n * 2 + 1] = c[3];
            n++;
        }
        n = clipAxis(xyz, uv, n, sAxis, rect[0], false);
        n = clipAxis(xyz, uv, n, sAxis, rect[2], true);
        n = clipAxis(xyz, uv, n, tAxis, rect[1], false);
        n = clipAxis(xyz, uv, n, tAxis, rect[3], true);
        if (n < 3) return null;
        float[] outXyz = new float[n * 3], outUv = new float[n * 2];
        System.arraycopy(xyz, 0, outXyz, 0, n * 3);
        System.arraycopy(uv, 0, outUv, 0, n * 2);
        return new Polygon(outXyz, outUv, n);
    }

    /** Keeps the part of the polygon on the inner side of {@code coordinate[axis] = limit}. */
    private static int clipAxis(float[] xyz, float[] uv, int n, int axis, float limit, boolean keepBelow) {
        if (n == 0) return 0;
        float[] inXyz = xyz.clone(), inUv = uv.clone();
        int out = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float a = inXyz[i * 3 + axis], b = inXyz[j * 3 + axis];
            boolean aIn = keepBelow ? a <= limit : a >= limit;
            boolean bIn = keepBelow ? b <= limit : b >= limit;
            if (aIn) out = copy(inXyz, inUv, i, xyz, uv, out);
            if (aIn != bIn && out < 16) {
                float f = (limit - a) / (b - a);
                for (int k = 0; k < 3; k++) xyz[out * 3 + k] = inXyz[i * 3 + k] + (inXyz[j * 3 + k] - inXyz[i * 3 + k]) * f;
                for (int k = 0; k < 2; k++) uv[out * 2 + k] = inUv[i * 2 + k] + (inUv[j * 2 + k] - inUv[i * 2 + k]) * f;
                out++;
            }
        }
        return out;
    }

    private static int copy(float[] fromXyz, float[] fromUv, int i, float[] xyz, float[] uv, int out) {
        if (out >= 16) return out;
        System.arraycopy(fromXyz, i * 3, xyz, out * 3, 3);
        System.arraycopy(fromUv, i * 2, uv, out * 2, 2);
        return out + 1;
    }
}
