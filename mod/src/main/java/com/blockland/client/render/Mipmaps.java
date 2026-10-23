package com.blockland.client.render;

/**
 * Box-filtered mip levels on the CPU for {@link Textures}. Pixels are ABGR ints (NativeImage's layout: R in the low
 * byte). Colour is averaged in linear light; LabPBR specular maps keep their codes intact — a level is metal (and keeps
 * a metal id) when most of its source texels are, and "no emission" (alpha 255) is an off switch, not a brightness.
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
