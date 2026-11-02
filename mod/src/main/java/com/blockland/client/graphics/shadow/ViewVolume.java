package com.blockland.client.graphics.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * {@link com.blockland.client.perf.Optimization#SHADOW_VIEW_CASTERS}: where a caster has to be for its shadow to land on
 * anything this frame shows. Every shadow lookup is made at a camera-relative position inside the view frustum (the
 * terrain, entity, water and hand fragments are on screen; the volumetric light marches along view rays; the
 * per-vertex lit particles and paint sample at their own positions a block or so from their on-screen quads) and no
 * farther from the camera than the cascade's reach — the shaders pick a cascade by distance (scene.glsl). A caster
 * shadows the positions behind it along the light, so it matters only if it lies inside the frustum swept towards the
 * light: the frustum's planes that face away from the light still bound that volume, the others open up. The frustum
 * is widened by a margin on every side first ({@link #LIT_MARGIN}: for the shadow taps' own reach ({@link Cascade#TAP_REACH}), the
 * first-person weapon (drawn with its own projection and shadowed by the map, within a couple of blocks of the camera —
 * every point that near lies inside the widened frustum), the smoke and debris quads lit at their centres while partly
 * on screen, the projection's jitter, and float rounding; {@link #SAMPLED_MARGIN} while the world is unlit and only the
 * volumetric light samples the map, along view rays).
 * <p>
 * The test is conservative, never tight: a box that passes every kept plane may still miss the swept frustum (the
 * swept volume's own side faces are left out), but a box that fails one cannot shadow anything in view — moving
 * along the light towards the frustum only takes it further outside that plane. Everything is camera-relative.
 */
final class ViewVolume {
    /** How far outside the frustum a shadow lookup can be made (blocks) while the world is lit; see the class comment. */
    static final float LIT_MARGIN = 16f;
    /**
     * The margin while nothing but the volumetric light reads the map (the world unlit): its samples lie on view rays
     * through the half-resolution pixels, inside the frustum, and the hardware filter reaches a texel.
     */
    static final float SAMPLED_MARGIN = 2f;
    private float margin = LIT_MARGIN;
    /** Plane i: n·p ≤ d for the camera-relative points inside (n unit, d at index 3), the six frustum planes. */
    private final float[] planes = new float[6 * 4];
    private final Vector3f forward = new Vector3f(0, 0, -1);
    private final Matrix4f scratch = new Matrix4f();
    /** The combined matrix, column-major ({@code m[column * 4 + row]}). */
    private final float[] m = new float[16];
    private boolean valid;

    /**
     * The view frustum this frame: the level's projection (unjittered) and the camera's rotation (camera-relative
     * world → view), each plane pushed outwards by the margin.
     */
    void set(Matrix4f projection, Matrix4f viewRotation, float margin) {
        this.margin = margin;
        scratch.set(projection).mul(viewRotation);
        // Gribb–Hartmann: the clip-space planes of P·V are sums and differences of the matrix's rows, each (a, b, c, d)
        // with a·x + b·y + c·z + d ≥ 0 inside; row r is (m[0*4+r], m[1*4+r], m[2*4+r], m[3*4+r])
        scratch.get(m);
        plane(0, m, 3, 0, +1); // left:   row3 + row0
        plane(1, m, 3, 0, -1); // right:  row3 - row0
        plane(2, m, 3, 1, +1); // bottom: row3 + row1
        plane(3, m, 3, 1, -1); // top:    row3 - row1
        plane(4, m, 3, 2, +1); // near:   row3 + row2
        plane(5, m, 3, 2, -1); // far:    row3 - row2
        // the view direction in camera-relative world: the rotation takes world to view, so view −Z back to world is the
        // transpose applied to (0, 0, −1) — minus the matrix's third row
        forward.set(-viewRotation.m02(), -viewRotation.m12(), -viewRotation.m22());
        forward.normalize();
        valid = true;
    }

    /** Plane {@code i} from rows {@code a} ± {@code b} of a column-major 4×4, normalised and pushed out by the margin. */
    private void plane(int i, float[] m, int a, int b, float sign) {
        float x = m[0 * 4 + a] + sign * m[0 * 4 + b];
        float y = m[1 * 4 + a] + sign * m[1 * 4 + b];
        float z = m[2 * 4 + a] + sign * m[2 * 4 + b];
        float w = m[3 * 4 + a] + sign * m[3 * 4 + b];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(len > 1e-9f)) {
            // a degenerate projection: no plane (everything passes it)
            planes[i * 4] = planes[i * 4 + 1] = planes[i * 4 + 2] = 0;
            planes[i * 4 + 3] = Float.MAX_VALUE;
            return;
        }
        // inside: a·p + d ≥ 0  ⇔  (−a)·p ≤ d; store n = −a/len, dist = d/len + margin
        planes[i * 4] = -x / len;
        planes[i * 4 + 1] = -y / len;
        planes[i * 4 + 2] = -z / len;
        planes[i * 4 + 3] = w / len + margin;
    }

    void invalidate() {
        valid = false;
    }

    boolean valid() {
        return valid;
    }

    /**
     * Prepares the test for one cascade placement: the frustum's planes that face away from the light (those with
     * n·light ≤ 0; the others are opened by the sweep) plus the reach plane (view depth ≤ reach + margin: every
     * position the cascade is asked about lies within its reach of the camera, so within that view depth; the plane
     * faces the view direction, kept only when it too faces away from the light), each moved to the cascade's
     * box-relative frame (box-relative = camera-relative − (centre − camera)).
     *
     * @param reach the cascade's split (blocks)
     * @param out   room for 7 planes × 4 floats; the number of planes written is returned
     */
    int sweep(Vector3f light, double centerX, double centerY, double centerZ, double camX, double camY, double camZ, float reach, float[] out) {
        if (!valid) return 0;
        // box-relative point b = camera-relative point p − o, o = centre − camera: n·p ≤ d  ⇔  n·b ≤ d − n·o
        float ox = (float) (centerX - camX), oy = (float) (centerY - camY), oz = (float) (centerZ - camZ);
        int n = 0;
        for (int i = 0; i < 6; i++) {
            float nx = planes[i * 4], ny = planes[i * 4 + 1], nz = planes[i * 4 + 2];
            if (nx * light.x + ny * light.y + nz * light.z > 0) continue; // opened by the sweep towards the light
            out[n * 4] = nx;
            out[n * 4 + 1] = ny;
            out[n * 4 + 2] = nz;
            out[n * 4 + 3] = planes[i * 4 + 3] - (nx * ox + ny * oy + nz * oz);
            n++;
        }
        if (forward.x * light.x + forward.y * light.y + forward.z * light.z <= 0) {
            out[n * 4] = forward.x;
            out[n * 4 + 1] = forward.y;
            out[n * 4 + 2] = forward.z;
            out[n * 4 + 3] = reach + margin - (forward.x * ox + forward.y * oy + forward.z * oz);
            n++;
        }
        return n;
    }

    /**
     * Whether a box (box-relative world coordinates) can lie inside the swept frustum given by {@link #sweep}: it is
     * left out only when it lies wholly outside one plane — its corner least far along the plane's normal is outside
     * too (a box that straddles a plane is kept). Conservative.
     */
    static boolean reaches(float[] planes, int count, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < count; i++) {
            float nx = planes[i * 4], ny = planes[i * 4 + 1], nz = planes[i * 4 + 2], d = planes[i * 4 + 3];
            float px = nx > 0 ? minX : maxX, py = ny > 0 ? minY : maxY, pz = nz > 0 ? minZ : maxZ;
            if (nx * px + ny * py + nz * pz > d) return false;
        }
        return true;
    }
}
