package com.blockland.client.graphics.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One cascade of the sun shadow map: an orthographic box around the camera, as seen from the light. The box is placed in
 * world space (centre in doubles, snapped to whole shadow texels along the light's axes so standing still or turning
 * never makes the shadows crawl) and only re-placed when the cascade is redrawn; between redraws its matrices are
 * re-derived for the moving camera from that placement, so a cascade drawn last frame still lines up exactly.
 */
final class Cascade {
    /** Radius of a 16³ section's bounding sphere. */
    static final float SECTION_RADIUS = 13.857f;
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

    // derived each frame for the current camera
    /** Camera-relative world → light view. */
    final Matrix4f view = new Matrix4f();
    /** Camera-relative world → the box as clip space (−1..1). */
    final Matrix4f clip = new Matrix4f();
    /** Camera-relative world → cascade-local [0,1]³ (what the shaders sample with). */
    final Matrix4f local = new Matrix4f();
    /** Camera-relative world → the cascade's tile of the atlas in clip space (what the casters are drawn with). */
    final Matrix4f render = new Matrix4f();
    private final Matrix4f tileTransform = new Matrix4f();
    /** A padded section box's half extent along each light axis ({@link #prepareBox}). */
    private float boxX, boxY, boxZ;

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
        valid = true;
    }

    /** Re-derives the matrices for this frame's camera position. */
    void update(double camX, double camY, double camZ) {
        double reach = half + up;
        float ex = (float) (centerX + light.x * reach - camX);
        float ey = (float) (centerY + light.y * reach - camY);
        float ez = (float) (centerZ + light.z * reach - camZ);
        view.set(rotation).translate(-ex, -ey, -ez);
        clip.setOrtho(-half, half, -half, half, 0, depth).mul(view);
        local.set(BIAS).mul(clip);
        render.set(tileTransform).mul(clip);
    }

    /** Distance of the camera from the box centre, across the light (how far it moved since the placement). */
    double drift(double camX, double camY, double camZ) {
        double dx = camX - centerX, dy = camY - centerY, dz = camZ - centerZ;
        double along = dx * light.x + dy * light.y + dz * light.z;
        return Math.sqrt(Math.max(0, dx * dx + dy * dy + dz * dz - along * along));
    }

    /**
     * Whether a sphere (camera-relative centre) can cast into this box: inside it across the light, and between the
     * box's light-facing end and its far end along the light.
     */
    boolean touches(float x, float y, float z, float radius, Vector3f scratch) {
        view.transformPosition(x, y, z, scratch);
        float r = half + radius;
        return Math.abs(scratch.x) <= r && Math.abs(scratch.y) <= r && scratch.z <= radius && scratch.z >= -depth - radius;
    }

    /**
     * Sets up {@link #boxReaches} for this frame: a section's box grown by {@code pad} blocks on every side spans
     * 8 + pad blocks around its centre along each world axis, so along each of the light's axes it spans that times the
     * sum of the axis' absolute world components.
     */
    void prepareBox(float pad) {
        float h = 8 + pad;
        boxX = h * (Math.abs(view.m00()) + Math.abs(view.m10()) + Math.abs(view.m20()));
        boxY = h * (Math.abs(view.m01()) + Math.abs(view.m11()) + Math.abs(view.m21()));
        boxZ = h * (Math.abs(view.m02()) + Math.abs(view.m12()) + Math.abs(view.m22()));
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
}
