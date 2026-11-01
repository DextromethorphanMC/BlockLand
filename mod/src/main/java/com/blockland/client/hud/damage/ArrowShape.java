package com.blockland.client.hud.damage;

import com.blockland.anim.ColorMath;

/**
 * One arrow's geometry (UI spec §3.5), built in the ring's space and projected through the {@link RingView}: a curved
 * band along the ring, thickest in its middle and thinning to points, with a notched arrowhead in its middle pointing
 * out towards the attacker, and a soft glow under it. The arrow tips about its own tangent towards the attacker's height
 * and lifts a little; a hit pops it (bigger, then settling with a small wobble), punches it towards the middle and
 * flashes it white-hot, cooling to its colour.
 */
final class ArrowShape {
    private static final int SEGMENTS = 12;

    /** What to draw: bearing (radians, 0 ahead, + to the right), elevation (radians), strength 0…1, age in seconds. */
    record Pose(float bearing, float elevation, float strength, float plateShare, float age, float kick) {
    }

    private final DamageIndicatorData d;
    private final RingView view;
    // the arrow's frame: its middle on the ring, the way out (radial) and along the ring (tangent) at its bearing
    private double bearing, sinB, cosB, scale, punch, tiltSin, tiltCos, lift;

    ArrowShape(DamageIndicatorData d, RingView view) {
        this.d = d;
        this.view = view;
    }

    /** Adds the arrow's glow to {@code glow} and its body to {@code body}, at opacity {@code alpha}. */
    void build(Pose p, float alpha, QuadMesh glow, QuadMesh body) {
        if (alpha <= 0.003f) return;
        bearing = p.bearing();
        sinB = Math.sin(bearing);
        cosB = Math.cos(bearing);
        float t = p.age();
        // the pop and the wobble as it settles; the punch in, deepest at punchTime, then back out
        scale = 1 + d.popScale * p.kick() * Math.exp(-d.popDamping * t) * Math.cos(d.popFrequency * t);
        double tp = t / Math.max(0.005, d.punchTime);
        punch = -d.punch * p.kick() * tp * Math.exp(1 - tp);
        double maxTilt = Math.toRadians(Math.max(0, Math.min(89, d.maxTilt)));
        double tilt = Math.max(-maxTilt, Math.min(maxTilt, p.elevation()));
        // never so far that it turns edge-on to the virtual camera: seen from it, the arrow's face (A cos t + B sin t
        // of the view) keeps at least minFacing — an arrow ahead may not sink as far as it may rise, one behind the other way
        double view = Math.toRadians(Math.max(5, Math.min(90, d.cameraTilt)));
        double a = Math.sin(view), b = cosB * Math.cos(view), most = Math.hypot(a, b);
        double floor = Math.sin(Math.toRadians(Math.max(0, Math.min(85, d.minFacing))));
        double toward = Math.atan2(b, a), room = Math.acos(Math.min(1, floor / most));
        tilt = Math.max(toward - room, Math.min(toward + room, tilt));
        tiltSin = Math.sin(tilt);
        tiltCos = Math.cos(tilt);
        lift = d.lift * Math.sin(p.elevation());

        float s = p.strength();
        float hot = 1 - smooth(t / Math.max(0.01f, d.flash));
        int base = ColorMath.lerpRgb(d.healthColor, d.platesColor, p.plateShare());
        int core = ColorMath.lerpRgb(base, d.hotColor, hot);
        int rim = ColorMath.lerpRgb(core, 0xFFFFFFFF, 0.3f + 0.4f * hot);

        double span = Math.toRadians(d.arc[0] + (d.arc[1] - d.arc[0]) * s);
        double thick = d.thickness * (0.75 + 0.25 * s);

        // the glow: wider than the band, brightest along the ring, more of it while the hit is hot
        float glowAlpha = alpha * d.glowAlpha * (1 + 1.4f * hot);
        int glowColor = ColorMath.lerpRgb(base, d.hotColor, hot * 0.6f);
        double spread = thick * d.glowSpread;
        band(glow, span * 1.2, new double[]{-spread, 0, spread + d.headLength * 0.45},
                new int[]{0, argb(glowColor, glowAlpha), 0});

        // the band: a soft edge, the body (darker in, brighter at its outer rim), a soft edge
        double f = d.feather;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = -span / 2 + span * i / SEGMENTS, a1 = -span / 2 + span * (i + 1) / SEGMENTS;
            double w0 = thick * taper(a0, span), w1 = thick * taper(a1, span);
            float k0 = (float) (0.55 + 0.45 * taper(a0, span)), k1 = (float) (0.55 + 0.45 * taper(a1, span));
            double[] r0 = {1 - w0 / 2 - f, 1 - w0 / 2, 1 + w0 / 2, 1 + w0 / 2 + f};
            double[] r1 = {1 - w1 / 2 - f, 1 - w1 / 2, 1 + w1 / 2, 1 + w1 / 2 + f};
            int[] c0 = {0, argb(core, alpha * k0 * 0.85f), argb(rim, alpha * k0), 0};
            int[] c1 = {0, argb(core, alpha * k1 * 0.85f), argb(rim, alpha * k1), 0};
            for (int k = 0; k < 3; k++) strip(body, r0[k], r0[k + 1], a0, r1[k], r1[k + 1], a1, c0[k], c0[k + 1], c1[k], c1[k + 1]);
        }

        // the arrowhead in the middle, out from the band's outer edge: a dart with a notch in its back
        double head = d.headLength * (0.8 + 0.2 * s), half = d.headWidth * (0.8 + 0.2 * s);
        double baseR = 1 + thick * 0.2, tipR = 1 + thick / 2 + head, notchR = baseR + head * 0.38, side = half / baseR;
        float[] l = point(baseR, -side), tip = point(tipR, 0), r = point(baseR, side), notch = point(notchR, 0);
        int back = argb(core, alpha * 0.9f), front = argb(rim, alpha);
        body.tri(l[0], l[1], back, tip[0], tip[1], front, notch[0], notch[1], back);
        body.tri(notch[0], notch[1], back, tip[0], tip[1], front, r[0], r[1], back);
    }

    /** A strip of the ring {@code span} long, through the given offsets from the ring and their colours. */
    private void band(QuadMesh mesh, double span, double[] offsets, int[] colors) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = -span / 2 + span * i / SEGMENTS, a1 = -span / 2 + span * (i + 1) / SEGMENTS;
            double t0 = taper(a0, span), t1 = taper(a1, span);
            for (int k = 0; k + 1 < offsets.length; k++) {
                strip(mesh, 1 + offsets[k] * t0, 1 + offsets[k + 1] * t0, a0, 1 + offsets[k] * t1, 1 + offsets[k + 1] * t1, a1,
                        scaleAlpha(colors[k], t0), scaleAlpha(colors[k + 1], t0), scaleAlpha(colors[k], t1), scaleAlpha(colors[k + 1], t1));
            }
        }
    }

    /** The quad between radii {@code in0}…{@code out0} at angle {@code a0} and {@code in1}…{@code out1} at {@code a1}. */
    private void strip(QuadMesh mesh, double in0, double out0, double a0, double in1, double out1, double a1,
                       int cIn0, int cOut0, int cIn1, int cOut1) {
        float[] p0 = point(in0, a0), p1 = point(out0, a0), p2 = point(out1, a1), p3 = point(in1, a1);
        mesh.quad(p0[0], p0[1], cIn0, p1[0], p1[1], cOut0, p2[0], p2[1], cOut1, p3[0], p3[1], cIn1);
    }

    /**
     * The point {@code radius} out from the ring's middle, {@code along} radians along the ring from the arrow's middle,
     * through the arrow's pop, punch, tilt and lift, on screen.
     */
    private float[] point(double radius, double along) {
        double a = bearing + along;
        double x = radius * Math.sin(a), z = -radius * Math.cos(a);
        // relative to the arrow's middle (sinB, 0, −cosB): along the tangent (cosB, 0, sinB) and out (sinB, 0, −cosB)
        double qx = x - sinB, qz = z + cosB;
        double alongT = (qx * cosB + qz * sinB) * scale;
        double outward = (qx * sinB - qz * cosB) * scale + punch;
        // tipped about the tangent: what lies further out rises (or sinks) towards the attacker's height
        double flat = outward * tiltCos, up = outward * tiltSin + lift;
        double px = sinB + alongT * cosB + flat * sinB;
        double pz = -cosB + alongT * sinB - flat * cosB;
        float[] screen = new float[2];
        view.project(px, up, pz, screen);
        return screen;
    }

    /** How thick the band is along its length: full in the middle, to a point at either end. */
    private static double taper(double along, double span) {
        double u = Math.min(1, Math.abs(2 * along / span));
        return Math.sqrt(Math.max(0, 1 - u * u));
    }

    private static float smooth(float x) {
        float t = Math.max(0, Math.min(1, x));
        return t * t * (3 - 2 * t);
    }

    private static int argb(int rgb, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return a << 24 | rgb & 0xFFFFFF;
    }

    private static int scaleAlpha(int argb, double factor) {
        int a = (int) Math.round((argb >>> 24) * Math.max(0, Math.min(1, factor)));
        return a << 24 | argb & 0xFFFFFF;
    }
}
