package com.blockland.client.gltf;

import org.joml.Quaternionf;

/**
 * Samples one animation into a {@link Pose}. Keeps the last key index per channel, so playback (which moves
 * forward in time) finds the next key in O(1) instead of searching every frame.
 */
public final class ClipSampler {
    private final GltfModel.Animation clip;
    private final int[] cursor;
    private final Quaternionf a = new Quaternionf();
    private final Quaternionf b = new Quaternionf();

    public ClipSampler(GltfModel.Animation clip) {
        this.clip = clip;
        this.cursor = new int[clip.channels.size()];
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
            if (k < 0 || keys == 1) {
                System.arraycopy(ch.values, 0, out, o, width);
                continue;
            }
            if (k >= keys - 1) {
                System.arraycopy(ch.values, (keys - 1) * width, out, o, width);
                continue;
            }
            float t0 = times[k], t1 = times[k + 1];
            float f = ch.step || t1 <= t0 ? 0 : (time - t0) / (t1 - t0);
            int i0 = k * width, i1 = (k + 1) * width;
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
