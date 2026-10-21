package com.blockland.anim;

/**
 * ARGB colour helpers. Mixing happens in OKLab, a perceptual colour space, so transitions (e.g. green → red)
 * stay vivid instead of passing through muddy greys as plain RGB interpolation does.
 */
public final class ColorMath {
    private ColorMath() {
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    /** Multiplies the colour's alpha by {@code factor} (0..1). */
    public static int fade(int argb, float factor) {
        return withAlpha(argb, Math.round(alpha(argb) * Math.max(0, Math.min(1, factor))));
    }

    /** Plain per-channel interpolation (fine for alpha-only or near colours). */
    public static int lerpRgb(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** Perceptual interpolation in OKLab (alpha interpolated linearly). */
    public static int mix(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        double[] la = toOklab(a);
        double[] lb = toOklab(b);
        double l = la[0] + (lb[0] - la[0]) * t;
        double m = la[1] + (lb[1] - la[1]) * t;
        double s = la[2] + (lb[2] - la[2]) * t;
        int alpha = Math.round((a >>> 24) + ((b >>> 24) - (a >>> 24)) * t);
        return fromOklab(l, m, s, alpha);
    }

    /** Changes perceptual lightness by {@code amount} (-1..1). */
    public static int lighten(int argb, float amount) {
        double[] lab = toOklab(argb);
        return fromOklab(Math.max(0, Math.min(1, lab[0] + amount)), lab[1], lab[2], argb >>> 24);
    }

    private static double[] toOklab(int argb) {
        double r = toLinear(((argb >> 16) & 0xFF) / 255.0);
        double g = toLinear(((argb >> 8) & 0xFF) / 255.0);
        double b = toLinear((argb & 0xFF) / 255.0);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static int fromOklab(double lightness, double a, double b, int alpha) {
        double l = Math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3);
        double m = Math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3);
        double s = Math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3);
        int r = channel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        int g = channel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        int bl = channel(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
        return (Math.max(0, Math.min(255, alpha)) << 24) | (r << 16) | (g << 8) | bl;
    }

    private static double toLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int channel(double linear) {
        double c = linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(Math.max(0, linear), 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, c)) * 255);
    }
}
