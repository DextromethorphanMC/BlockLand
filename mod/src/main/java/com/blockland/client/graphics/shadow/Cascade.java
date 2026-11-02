package com.blockland.client.graphics.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One cascade of the sun shadow map: an orthographic box around the camera, as seen from the light. The box is placed in
 * world space (centre in doubles, snapped to whole shadow texels along the light's axes so standing still or turning
 * never makes the shadows crawl) and kept while the camera stays within {@link #slack} of its centre and the light is
 * the one it was placed with; between placements its matrices are re-derived for the moving camera, so a cascade drawn
 * earlier still lines up exactly. Keeping the box shows the very picture that placing it around the camera every frame
 * would: the texel grid is anchored to the world (the snapping), the shaders pick a cascade by the distance from the
 * camera and then only ask whether the position lies inside its box — and within the slack every position nearer than
 * the reach does, with every shadow tap ({@link #TAP_REACH}) still inside the tile.
 * <p>
 * Two frames of reference: the terrain casters are placed relative to the box's centre ({@link #casterView},
 * {@link #terrainRender}: fixed with the placement, so the same sections give the same drawing whatever the camera does
 * meanwhile — what lets {@code TerrainCache} keep it while the camera moves), the entities and the receivers relative to
 * the camera ({@link #view}, {@link #clip}, {@link #local}, {@link #entityRender}: re-derived each frame).
 */
final class Cascade {
    /** Radius of a 16³ section's bounding sphere. */
    static final float SECTION_RADIUS = 13.857f;
    /**
     * How far from a looked-up position the shaders' shadow taps reach (blocks): the soft shadow's blocker search goes
     * out to 1.5 blocks (scene.glsl), the filter's own radius is a texel or two.
     */
    static final float TAP_REACH = 1.6f;
    private static final Matrix4f BIAS = new Matrix4f().translation(0.5f, 0.5f, 0.5f).scale(0.5f);

    final int index;
    /** Reach from the camera (blocks): positions closer than this are shadowed by this cascade. */
    float split;
    /** Half the side of the box (the reach plus a margin for filtering). */
    float half;
    /** How far the box reaches past the camera towards the light (casters above: roofs, trees, terrain over caves). */
    float up;
    /** Depth of the box along the light. */
    float depth;
    /** Side of the cascade's tile in texels. */
    int tile;
    /** The cascade holds a drawing (placed at least once with the current atlas). */
    boolean valid;
    /** The direction towards the light it was placed with. */
    final Vector3f light = new Vector3f(0, 1, 0);
    /** World axes → light axes (−Z along the light's rays). */
    final Matrix4f rotation = new Matrix4f();
    /** Snapped box centre in world space. */
    double centerX, centerY, centerZ;

    // fixed with the placement (the terrain casters)
    /** Box-relative world (positions minus the box centre) → light view. */
    final Matrix4f casterView = new Matrix4f();
    /** Box-relative world → the cascade's tile of the atlas in clip space (what the terrain casters are drawn with). */
    final Matrix4f terrainRender = new Matrix4f();
    /** A padded section box's half extent along each light axis ({@link #prepareBox}). */
    private float boxX, boxY, boxZ;
    private final Matrix4f casterClip = new Matrix4f();

    // derived each frame for the current camera (the entities, the receivers)
    /** Camera-relative world → light view. */
    final Matrix4f view = new Matrix4f();
    /** Camera-relative world → the box as clip space (−1..1). */
    final Matrix4f clip = new Matrix4f();
    /** Camera-relative world → cascade-local [0,1]³ (what the shaders sample with). */
    final Matrix4f local = new Matrix4f();
    /** Camera-relative world → the cascade's tile of the atlas in clip space (what the entity casters are drawn with). */
    final Matrix4f entityRender = new Matrix4f();
    private final Matrix4f tileTransform = new Matrix4f();
    /**
     * The farthest any caster drawn into this placement lies from the box centre (blocks), grown as casters are drawn
     * ({@link #lightDrift}); 0 after a placement.
     */
    float reach;
    /** Counts the placements ({@link #place}): equal serials, the same box and light. */
    int serial;
    private final Vector3f scratchCross = new Vector3f();
    /** Where the kept caster walk was made (SHADOW_GATHER_CACHE, {@link #walked}): the centre, the light, the size. */
    private double walkX, walkY, walkZ;
    private final Vector3f walkLight = new Vector3f();
    private float walkHalf, walkUp;
    private boolean walked;

    Cascade(int index) {
        this.index = index;
        float ox = (index & 1) - 0.5f, oy = (index >> 1) - 0.5f;
        tileTransform.translation(ox, oy, 0).scale(0.5f, 0.5f, 1f);
    }

    /** The tile's centre in the atlas, normalised device coordinates. */
    float tileX() {
        return (index & 1) - 0.5f;
    }

    float tileY() {
        return (index >> 1) - 0.5f;
    }

    /** Places the box around the camera for a redraw. */
    void place(Vector3f towardsLight, double camX, double camY, double camZ, float reach, int tileSize, float extension) {
        split = reach;
        tile = tileSize;
        half = reach * 1.04f + 2f;
        up = extension;
        depth = 2 * half + up;
        light.set(towardsLight);
        // light axes: −Z along the rays, +Y towards the north-south axis (never parallel: the sun's path is tilted < 90°)
        rotation.setLookAlong(-light.x, -light.y, -light.z, 0, 0, 1);
        double texel = 2.0 * half / tileSize;
        double m00 = rotation.m00(), m01 = rotation.m01(), m02 = rotation.m02();
        double m10 = rotation.m10(), m11 = rotation.m11(), m12 = rotation.m12();
        double m20 = rotation.m20(), m21 = rotation.m21(), m22 = rotation.m22();
        double qx = m00 * camX + m10 * camY + m20 * camZ;
        double qy = m01 * camX + m11 * camY + m21 * camZ;
        double qz = m02 * camX + m12 * camY + m22 * camZ;
        qx = Math.floor(qx / texel) * texel;
        qy = Math.floor(qy / texel) * texel;
        centerX = m00 * qx + m01 * qy + m02 * qz;
        centerY = m10 * qx + m11 * qy + m12 * qz;
        centerZ = m20 * qx + m21 * qy + m22 * qz;
        // the box's light-facing end lies `reach` along the light from the centre: the casters' eye point
        float reachAlong = half + up;
        casterView.set(rotation).translate(-light.x * reachAlong, -light.y * reachAlong, -light.z * reachAlong);
        casterClip.setOrtho(-half, half, -half, half, 0, depth).mul(casterView);
        terrainRender.set(tileTransform).mul(casterClip);
        reach = 0;
        serial++;
        valid = true;
    }

    /**
     * How far, in this cascade's texels, a caster {@link #reach} from the box centre moves across the map when the
     * light turns from the placement's direction to {@code towardsLight}: the angle between them (both unit) times
     * that distance, over a texel's size. Every shadow edge the kept placement shows lies within that of a fresh one
     * (the receivers sample with the placement's own matrices, so the map and the lookups agree).
     */
    float lightDrift(Vector3f towardsLight) {
        float angle = (float) Math.atan2(light.cross(towardsLight, scratchCross).length(), light.dot(towardsLight));
        return angle * reach * tile / (2 * half);
    }

    /** The caster walk was just made with the box as it is now ({@link #withinWalk}). */
    void walked() {
        walkX = centerX;
        walkY = centerY;
        walkZ = centerZ;
        walkLight.set(light);
        walkHalf = half;
        walkUp = up;
        walked = true;
    }

    /**
     * Whether every point of the box lies within {@code margin} blocks of where it was when the kept walk was made: the
     * centre moved by less, and the light's turn moves the farthest corner by less, together. Then every section whose
     * sphere touches the box now touched the box then grown by the margin.
     */
    boolean withinWalk(float margin) {
        if (!walked || walkHalf != half || walkUp != up) return false;
        double dx = centerX - walkX, dy = centerY - walkY, dz = centerZ - walkZ;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float angle = (float) Math.atan2(walkLight.cross(light, scratchCross).length(), walkLight.dot(light));
        return moved + angle * boxReach() <= margin;
    }

    /** The farthest the box's own corners lie from its centre (blocks): the reach of casters that are not known. */
    float boxReach() {
        float along = half + up;
        return (float) Math.sqrt(2 * half * half + along * along);
    }

    /** The farthest corner of a box given relative to the camera at {@code cam} from the box centre (blocks). */
    float farthest(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, double camX, double camY, double camZ) {
        double ox = camX - centerX, oy = camY - centerY, oz = camZ - centerZ;
        double dx = Math.max(Math.abs(ox + minX), Math.abs(ox + maxX)), dy = Math.max(Math.abs(oy + minY), Math.abs(oy + maxY));
        double dz = Math.max(Math.abs(oz + minZ), Math.abs(oz + maxZ));
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Re-derives the camera-relative matrices for this frame's camera position. */
    void update(double camX, double camY, double camZ) {
        double reach = half + up;
        float ex = (float) (centerX + light.x * reach - camX);
        float ey = (float) (centerY + light.y * reach - camY);
        float ez = (float) (centerZ + light.z * reach - camZ);
        view.set(rotation).translate(-ex, -ey, -ez);
        clip.setOrtho(-half, half, -half, half, 0, depth).mul(view);
        local.set(BIAS).mul(clip);
        entityRender.set(tileTransform).mul(clip);
    }

    /** Distance of the camera from the box centre (how far it moved since the placement). */
    double drift(double camX, double camY, double camZ) {
        double dx = camX - centerX, dy = camY - centerY, dz = camZ - centerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * How far the camera may be from the box centre for the kept placement to show what a fresh one would: the box
     * reaches {@link #half} from its centre and the cascade is used out to {@link #split} from the camera, so every
     * position it is asked about stays {@code half - split - drift} inside the box — enough for every tap while that is
     * at least {@link #TAP_REACH}. (Along the light the box reaches further still: {@link #up} towards the light.)
     */
    double slack() {
        return half - split - TAP_REACH;
    }

    /**
     * Whether a sphere (box-relative centre: world position minus {@link #centerX}, {@link #centerY}, {@link #centerZ})
     * can cast into this box: inside it across the light, and between the box's light-facing end and its far end along
     * the light. Decided from the placement alone, so the same sections reach the box on every frame it is kept.
     */
    boolean touches(float x, float y, float z, float radius, Vector3f scratch) {
        casterView.transformPosition(x, y, z, scratch);
        float r = half + radius;
        return Math.abs(scratch.x) <= r && Math.abs(scratch.y) <= r && scratch.z <= radius && scratch.z >= -depth - radius;
    }

    /**
     * Sets up {@link #boxReaches} for this placement: a section's box grown by {@code pad} blocks on every side spans
     * 8 + pad blocks around its centre along each world axis, so along each of the light's axes it spans that times the
     * sum of the axis' absolute world components.
     */
    void prepareBox(float pad) {
        float h = 8 + pad;
        boxX = h * (Math.abs(casterView.m00()) + Math.abs(casterView.m10()) + Math.abs(casterView.m20()));
        boxY = h * (Math.abs(casterView.m01()) + Math.abs(casterView.m11()) + Math.abs(casterView.m21()));
        boxZ = h * (Math.abs(casterView.m02()) + Math.abs(casterView.m12()) + Math.abs(casterView.m22()));
    }

    /** Over a mesh box's light-space extent: float rounding of this test against the shader's own transform (far below). */
    private static final float MESH_MARGIN = 0.05f;

    /**
     * Whether a box of geometry (box-relative world coordinates, the box round a section mesh's vertices) overlaps this
     * cascade's box (SHADOW_CULLING): its eight corners in the light's axes bound every vertex and so every fragment
     * (orthographic); outside the box across the light the tile's scissor takes them, outside it along the light the
     * near and far planes do, so a mesh that fails this draws nothing into the cascade.
     */
    boolean meshReaches(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Vector3f scratch) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, bottom = Float.MAX_VALUE, top = -Float.MAX_VALUE;
        float near = -Float.MAX_VALUE, far = Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            casterView.transformPosition((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? minZ : maxZ, scratch);
            lo = Math.min(lo, scratch.x);
            hi = Math.max(hi, scratch.x);
            bottom = Math.min(bottom, scratch.y);
            top = Math.max(top, scratch.y);
            near = Math.max(near, scratch.z);
            far = Math.min(far, scratch.z);
        }
        float h = half + MESH_MARGIN;
        return hi >= -h && lo <= h && top >= -h && bottom <= h && near >= -depth - MESH_MARGIN && far <= MESH_MARGIN;
    }

    /**
     * Whether a section's padded box ({@link #prepareBox}) overlaps this box (SHADOW_CULLING): {@code centre} is the
     * section's centre in light space, as {@link #touches} left it. Geometry outside the box on any light axis is
     * clipped (scissored to the tile across the light, near and far planes along it), so a section that fails this
     * draws nothing into the cascade.
     */
    boolean boxReaches(Vector3f centre) {
        return Math.abs(centre.x) <= half + boxX && Math.abs(centre.y) <= half + boxY && centre.z <= boxZ && centre.z >= -depth - boxZ;
    }

    /**
     * The atlas texels (x, y, width, height into {@code rect}) that drawing anything inside a box can touch in this
     * cascade's tile: the box's eight corners through {@code render} ({@link #terrainRender} for a box-relative box,
     * {@link #entityRender} for a camera-relative one) bound the fragments of every triangle inside it (orthographic;
     * a fragment's centre lies inside its triangle, so inside the corners' bounds), rounded outwards to whole texels
     * and clipped to the tile. False when nothing of the box lands in the tile (the rect is then empty).
     */
    boolean tileRect(Matrix4f render, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int[] rect, Vector3f scratch) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, bottom = Float.MAX_VALUE, top = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            render.transformPosition((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? minZ : maxZ, scratch);
            lo = Math.min(lo, scratch.x);
            hi = Math.max(hi, scratch.x);
            bottom = Math.min(bottom, scratch.y);
            top = Math.max(top, scratch.y);
        }
        // atlas NDC → atlas texels (the atlas is two tiles wide), a texel of margin for the GPU's own rounding of the
        // same transform, clipped to this tile
        int x0 = (index & 1) * tile, y0 = (index >> 1) * tile;
        int x = Math.max(x0, (int) Math.floor((lo + 1) * tile) - 1), y = Math.max(y0, (int) Math.floor((bottom + 1) * tile) - 1);
        int x1 = Math.min(x0 + tile, (int) Math.ceil((hi + 1) * tile) + 1), y1 = Math.min(y0 + tile, (int) Math.ceil((top + 1) * tile) + 1);
        rect[0] = x;
        rect[1] = y;
        rect[2] = Math.max(0, x1 - x);
        rect[3] = Math.max(0, y1 - y);
        return rect[2] > 0 && rect[3] > 0;
    }

    /** The whole tile into {@code rect}. */
    void tileRect(int[] rect) {
        rect[0] = (index & 1) * tile;
        rect[1] = (index >> 1) * tile;
        rect[2] = tile;
        rect[3] = tile;
    }

    /** Grows {@code a} to hold {@code b} as well (either may be empty: width 0). */
    static void union(int[] a, int[] b) {
        if (b[2] <= 0 || b[3] <= 0) return;
        if (a[2] <= 0 || a[3] <= 0) {
            System.arraycopy(b, 0, a, 0, 4);
            return;
        }
        int x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
        int x1 = Math.max(a[0] + a[2], b[0] + b[2]), y1 = Math.max(a[1] + a[3], b[1] + b[3]);
        a[0] = x;
        a[1] = y;
        a[2] = x1 - x;
        a[3] = y1 - y;
    }

    static boolean intersects(int[] a, int[] b) {
        return a[2] > 0 && a[3] > 0 && b[2] > 0 && b[3] > 0
                && a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }
}
