package com.blockland.client.render;

/**
 * Box-filtered mip levels on the CPU for {@link Textures}. Pixels are ABGR ints (NativeImage's layout: R in the low
 * byte). Colour is averaged in linear light; LabPBR specular maps keep their codes intact — a level is metal (and keeps
 * a metal id) when most of its source texels are, and "no emission" (alpha 255) is an off switch, not a brightness.
 * A set's specular levels can also take its normal map's averaged-away relief as roughness ({@link #roughen}).
 */
final class Mipmaps {
    private static final float[] TO_LINEAR = new float[256];
    private static final int ENCODE_STEPS = 4096;
    private static final int[] TO_SRGB = new int[ENCODE_STEPS + 1];

    static {
        for (int i = 0; i < 256; i++) TO_LINEAR[i] = (float) Math.pow(i / 255.0, 2.2);
        for (int i = 0; i <= ENCODE_STEPS; i++) TO_SRGB[i] = (int) Math.round(Math.pow(i / (double) ENCODE_STEPS, 1 / 2.2) * 255);
    }

    private Mipmaps() {
    }

    /** The next level ({@code nw} × {@code nh}, each pixel from up to 2×2 source pixels). */
    static int[] down(int[] src, int w, int h, int nw, int nh, Textures.Kind kind) {
        int[] out = new int[nw * nh];
        int[] block = new int[4];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int n = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int sy = Math.min(h - 1, y * 2 + dy);
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.min(w - 1, x * 2 + dx);
                        block[n++] = src[sy * w + sx];
                    }
                }
                out[y * nw + x] = switch (kind) {
                    case COLOR -> color(block);
                    case DATA -> data(block);
                    case SPECULAR -> specular(block);
                };
            }
        }
        return out;
    }

    private static int channel(int abgr, int shift) {
        return abgr >>> shift & 0xFF;
    }

    private static int pack(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | r & 0xFF;
    }

    private static int encode(float linear) {
        return TO_SRGB[Math.round(Math.max(0, Math.min(1, linear)) * ENCODE_STEPS)];
    }

    /** Colour weighted by alpha, so the invisible (often black) texels around a cut-out do not darken its edge. */
    static int color(int[] p) {
        float r = 0, g = 0, b = 0, weight = 0;
        int a = 0;
        for (int c : p) {
            int alpha = channel(c, 24);
            float w = alpha == 0 ? 0.001f : alpha / 255f;
            r += TO_LINEAR[channel(c, 0)] * w;
            g += TO_LINEAR[channel(c, 8)] * w;
            b += TO_LINEAR[channel(c, 16)] * w;
            weight += w;
            a += alpha;
        }
        return pack(encode(r / weight), encode(g / weight), encode(b / weight), (a + 2) / 4);
    }

    static int data(int[] p) {
        int r = 0, g = 0, b = 0, a = 0;
        for (int c : p) {
            r += channel(c, 0);
            g += channel(c, 8);
            b += channel(c, 16);
            a += channel(c, 24);
        }
        return pack((r + 2) / 4, (g + 2) / 4, (b + 2) / 4, (a + 2) / 4);
    }

    /** Unit normals (x, y, z per texel) of a LabPBR {@code _n} map, z rebuilt from x and y. */
    static float[] normals(int[] abgr) {
        float[] out = new float[abgr.length * 3];
        for (int i = 0; i < abgr.length; i++) {
            float x = channel(abgr[i], 0) / 127.5f - 1, y = channel(abgr[i], 8) / 127.5f - 1;
            float z = (float) Math.sqrt(Math.max(0, 1 - x * x - y * y));
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            out[i * 3] = x / len;
            out[i * 3 + 1] = y / len;
            out[i * 3 + 2] = z / len;
        }
        return out;
    }

    /** The next level of averaged normals, not renormalised: how much shorter they get measures the detail averaged away. */
    static float[] downNormals(float[] src, int w, int h, int nw, int nh) {
        float[] out = new float[nw * nh * 3];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int o = (y * nw + x) * 3;
                for (int dy = 0; dy < 2; dy++) {
                    int sy = Math.min(h - 1, y * 2 + dy);
                    for (int dx = 0; dx < 2; dx++) {
                        int s = (sy * w + Math.min(w - 1, x * 2 + dx)) * 3;
                        out[o] += src[s] * 0.25f;
                        out[o + 1] += src[s + 1] * 0.25f;
                        out[o + 2] += src[s + 2] * 0.25f;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Toksvig: lowers the smoothness of a {@code _s} level by the spread of the normals each texel averages ({@code
     * normals} from {@link #downNormals}, same level), adding twice their variance to the GGX α² — capped like gun.fsh's
     * geometric specular anti-aliasing. Relief too fine to see keeps breaking up the highlight instead of vanishing.
     */
    static void roughen(int[] specular, float[] normals) {
        for (int i = 0; i < specular.length; i++) {
            float x = normals[i * 3], y = normals[i * 3 + 1], z = normals[i * 3 + 2];
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len >= 0.9999f) continue;
            float variance = (1 - len) / Math.max(len, 1e-4f);
            int c = specular[i];
            float rough = 1 - channel(c, 0) / 255f;
            float alpha = rough * rough;
            float wider = (float) Math.sqrt(Math.sqrt(Math.min(1, alpha * alpha + Math.min(2 * variance, 0.18f))));
            int smooth = Math.round((1 - wider) * 255);
            specular[i] = (c & 0xFFFFFF00) | Math.min(channel(c, 0), Math.max(0, smooth));
        }
    }

    /** LabPBR {@code _s}: R smoothness, G F0 (≥ 230 = metal ids), B porosity / SSS, A emission (255 = none). */
    static int specular(int[] p) {
        int smooth = 0, porosity = 0, metals = 0, metalId = 0, dielectric = 0, dielectrics = 0;
        float emission = 0;
        for (int c : p) {
            smooth += channel(c, 0);
            porosity += channel(c, 16);
            int f0 = channel(c, 8);
            if (f0 >= 230) {
                metals++;
                metalId = Math.max(metalId, f0);
            } else {
                dielectric += f0;
                dielectrics++;
            }
            int e = channel(c, 24);
            emission += e == 255 ? 0 : e / 254f;
        }
        int g = metals >= 2 ? metalId : dielectric / Math.max(1, dielectrics);
        float e = emission / 4;
        int a = e <= 0.5f / 254 ? 255 : Math.min(254, Math.round(e * 254));
        return pack((smooth + 2) / 4, g, (porosity + 2) / 4, a);
    }
}
