package com.blockland.client.hud.damage;

/**
 * The virtual camera the arrows are seen through (UI spec §3.5): the ring they lie on is level (radius 1, its far side
 * −Z = straight ahead of the player, +X to their right), and the camera looks down at its middle from behind and above
 * at {@code cameraTilt}, in perspective — so arrows ahead sit at the far side, smaller, those behind at the near side,
 * larger. The projection is fitted so the ring with its arrows fills the element's box.
 */
final class RingView {
    private final double sin, cos, distance, focal;
    private final float cx, cy;
    private double scale = 1, offX, offY;

    RingView(DamageIndicatorData d, float cx, float cy, float size) {
        double tilt = Math.toRadians(Math.max(5, Math.min(90, d.cameraTilt)));
        sin = Math.sin(tilt);
        cos = Math.cos(tilt);
        distance = Math.max(1.8, d.cameraDistance);
        focal = 1 / Math.tan(Math.toRadians(Math.max(10, Math.min(120, d.fov))) / 2);
        this.cx = cx;
        this.cy = cy;
        // fit: the ring as far out as the arrows and their glow reach
        double reach = 1 + d.thickness * (0.5 + d.glowSpread) + d.headLength;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        float[] p = new float[2];
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            raw(reach * Math.sin(a), 0, -reach * Math.cos(a), p);
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        scale = size * 0.96 / Math.max(1e-6, Math.max(maxX - minX, maxY - minY));
        offX = (minX + maxX) / 2;
        offY = (minY + maxY) / 2;
    }

    /** The ring-space point (x, y, z) on screen (GUI pixels) into {@code out}. */
    void project(double x, double y, double z, float[] out) {
        raw(x, y, z, out);
        out[0] = (float) (cx + (out[0] - offX) * scale);
        out[1] = (float) (cy + (out[1] - offY) * scale);
    }

    private void raw(double x, double y, double z, float[] out) {
        // the camera sits at distance · (0, sin, cos), looking at the middle: right +X, up (0, cos, −sin)
        double qy = y - distance * sin, qz = z - distance * cos;
        double depth = Math.max(0.05, -qy * sin - qz * cos);
        double up = qy * cos - qz * sin;
        out[0] = (float) (x / depth * focal);
        out[1] = (float) (-up / depth * focal);
    }
}
