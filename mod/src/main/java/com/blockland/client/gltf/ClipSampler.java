package com.blockland.client.gltf;

import org.joml.Quaternionf;

/**
 * Samples one animation into a {@link Pose} (STEP, LINEAR with slerped rotations, or CUBICSPLINE). Keeps the last key index per channel, so playback (which moves
 * forward in time) finds the next key in O(1) instead of searching every frame.
 * <p>
 * Densely baked LINEAR channels (keys at least {@link #DENSE} apart, asset spec §15 / §31) are read as a monotone cubic
 * through their keys instead of straight segments: at high refresh rates a 60 Hz bake would otherwise move in visible
 * polyline steps (the velocity jumping at every key). Monotone tangents never overshoot, so holds stay still; at a
 * turning point (a slide hitting home, a magazine seating, a peak of the kick) the segment keeps its own secant
 * instead of a zero tangent, so impacts arrive at full speed and stay sharp. Sparse channels (a few keys) stay linear.
 */
public final class ClipSampler {
    private final GltfModel.Animation clip;
    private final int[] cursor;
    /** Key spacing (s) up to which a LINEAR channel counts as a dense bake and is read smoothly. */
    private static final float DENSE = 1 / 45f;
    private final Quaternionf a = new Quaternionf();
    private final Quaternionf b = new Quaternionf();
    /** Per channel: its keys are dense enough to read smoothly. */
    private final boolean[] smooth;

    public ClipSampler(GltfModel.Animation clip) {
        this.clip = clip;
        this.cursor = new int[clip.channels.size()];
        this.smooth = new boolean[clip.channels.size()];
        for (int c = 0; c < smooth.length; c++) {
            GltfModel.Channel ch = clip.channels.get(c);
            boolean dense = !ch.step && !ch.cubic && ch.times.length >= 4;
            for (int k = 1; dense && k < ch.times.length; k++) dense = ch.times[k] - ch.times[k - 1] <= DENSE + 1e-4f;
            smooth[c] = dense;
        }
    }

    public GltfModel.Animation clip() {
        return clip;
    }

    public float duration() {
        return clip.duration;
    }

    /** Writes the clip's values at {@code time} over the pose (nodes the clip does not touch are left alone). */
    public void sample(float time, Pose pose) {
        for (int c = 0; c < clip.channels.size(); c++) {
            GltfModel.Channel ch = clip.channels.get(c);
            float[] times = ch.times;
            int keys = times.length;
            if (keys == 0) continue;
            int k = locate(c, times, time);
            int width = ch.path == GltfModel.Path.ROTATION ? 4 : 3;
            float[] out = switch (ch.path) {
                case TRANSLATION -> pose.t;
                case ROTATION -> pose.r;
                case SCALE -> pose.s;
            };
            int o = ch.node * width;
            // cubic keys are stored as (in-tangent, value, out-tangent)
            int stride = ch.cubic ? width * 3 : width, valueOffset = ch.cubic ? width : 0;
            if (k < 0 || keys == 1) {
                System.arraycopy(ch.values, valueOffset, out, o, width);
                continue;
            }
            if (k >= keys - 1) {
                System.arraycopy(ch.values, (keys - 1) * stride + valueOffset, out, o, width);
                continue;
            }
            float t0 = times[k], t1 = times[k + 1];
            if (ch.cubic && !ch.step && t1 > t0) {
                hermite(ch.values, k, width, (time - t0) / (t1 - t0), t1 - t0, out, o);
                continue;
            }
            float f = ch.step || t1 <= t0 ? 0 : (time - t0) / (t1 - t0);
            if (smooth[c]) {
                monotone(ch.times, ch.values, k, width, f, out, o);
                continue;
            }
            int i0 = k * stride + valueOffset, i1 = (k + 1) * stride + valueOffset;
            if (width == 4) {
                a.set(ch.values[i0], ch.values[i0 + 1], ch.values[i0 + 2], ch.values[i0 + 3]);
                b.set(ch.values[i1], ch.values[i1 + 1], ch.values[i1 + 2], ch.values[i1 + 3]);
                a.slerp(b, f);
                out[o] = a.x;
                out[o + 1] = a.y;
                out[o + 2] = a.z;
                out[o + 3] = a.w;
            } else {
                for (int j = 0; j < 3; j++) out[o + j] = ch.values[i0 + j] + (ch.values[i1 + j] - ch.values[i0 + j]) * f;
            }
        }
    }

    /**
     * glTF CUBICSPLINE between key {@code k} and {@code k + 1}: Hermite basis on the values with the out-tangent of
     * {@code k} and the in-tangent of {@code k + 1} (tangents are per second, hence the scale by the key gap).
     * Rotations are renormalised.
     */
    private static void hermite(float[] v, int k, int width, float t, float gap, float[] out, int o) {
        int base0 = k * width * 3, base1 = (k + 1) * width * 3;
        float t2 = t * t, t3 = t2 * t;
        float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        float length = 0;
        for (int j = 0; j < width; j++) {
            float p0 = v[base0 + width + j], m0 = v[base0 + 2 * width + j] * gap;
            float p1 = v[base1 + width + j], m1 = v[base1 + j] * gap;
            float value = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
            out[o + j] = value;
            length += value * value;
        }
        if (width == 4 && length > 1e-12f) {
            float inv = (float) (1 / Math.sqrt(length));
            for (int j = 0; j < 4; j++) out[o + j] *= inv;
        }
    }

    /**
     * Monotone cubic (Steffen) between key {@code k} and {@code k + 1} of a dense LINEAR channel, per component, with the
     * tangents from the neighbouring keys. Rotation keys are baked in one hemisphere (§15); a neighbour on the other side
     * is flipped, and the result is renormalised.
     */
    private static void monotone(float[] times, float[] v, int k, int width, float t, float[] out, int o) {
        int n = times.length, km = Math.max(0, k - 1), kp = Math.min(n - 1, k + 2);
        float h0 = times[k] - times[km], h1 = times[k + 1] - times[k], h2 = times[kp] - times[k + 1];
        float sign0 = 1, sign1 = 1, sign2 = 1;
        if (width == 4) {
            sign0 = dot4(v, km, k) < 0 ? -1 : 1;
            sign1 = dot4(v, k, k + 1) < 0 ? -1 : 1;
            sign2 = dot4(v, k + 1, kp) < 0 ? -1 : 1;
        }
        float t2 = t * t, t3 = t2 * t;
        float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        float length = 0;
        for (int j = 0; j < width; j++) {
            float p1 = v[k * width + j], p2 = v[(k + 1) * width + j] * sign1;
            float p0 = v[km * width + j] * sign0, p3 = v[kp * width + j] * sign1 * sign2;
            float d0 = h0 > 1e-6f ? (p1 - p0) / h0 : 0, d1 = (p2 - p1) / h1, d2 = h2 > 1e-6f ? (p3 - p2) / h2 : 0;
            // at a turning point the segment keeps its own slope (an impact arrives / leaves at speed, as baked)
            float m1 = km == k ? d1 : d0 * d1 <= 0 ? d1 : steffen(d0, d1, h0, h1);
            float m2 = kp == k + 1 ? d1 : d1 * d2 <= 0 ? d1 : steffen(d1, d2, h1, h2);
            float value = h00 * p1 + h10 * h1 * m1 + h01 * p2 + h11 * h1 * m2;
            out[o + j] = value;
            length += value * value;
        }
        if (width == 4 && length > 1e-12f) {
            float inv = (float) (1 / Math.sqrt(length));
            for (int j = 0; j < 4; j++) out[o + j] *= inv;
        }
    }

    /** Steffen's monotone slope at a key from the secants on either side: zero at extremes, never overshooting. */
    private static float steffen(float d0, float d1, float h0, float h1) {
        if (d0 * d1 <= 0) return 0;
        float p = (d0 * h1 + d1 * h0) / (h0 + h1);
        return Math.signum(d0) * Math.min(Math.min(Math.abs(d0), Math.abs(d1)), 0.5f * Math.abs(p)) * 2;
    }

    private static float dot4(float[] v, int a, int b) {
        return v[a * 4] * v[b * 4] + v[a * 4 + 1] * v[b * 4 + 1] + v[a * 4 + 2] * v[b * 4 + 2] + v[a * 4 + 3] * v[b * 4 + 3];
    }

    /** Index of the last key at or before {@code time} (-1 before the first key). */
    private int locate(int channel, float[] times, float time) {
        int k = cursor[channel];
        if (k >= times.length || k < 0 || times[k] > time) k = 0; // time went backwards (restart): search from the start
        if (time < times[0]) {
            cursor[channel] = 0;
            return -1;
        }
        while (k + 1 < times.length && times[k + 1] <= time) k++;
        cursor[channel] = k;
        return k;
    }
}
