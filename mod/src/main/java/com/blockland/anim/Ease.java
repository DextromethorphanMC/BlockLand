package com.blockland.anim;

import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Easing curves, mirroring GSAP's ease set. An ease maps linear progress {@code t ∈ [0,1]} to eased progress
 * (which may overshoot for back/elastic). Use the constants, the factories, or {@link #parse(String)} with GSAP
 * syntax such as {@code "power3.out"}, {@code "back.out(1.7)"}, {@code "elastic.out(1, 0.3)"}, {@code "steps(5)"}.
 */
@FunctionalInterface
public interface Ease {

    double apply(double t);

    // ---- combinators --------------------------------------------------------------------------

    /** The ease run backwards in time: turns an "in" curve into the matching "out" curve. */
    static Ease out(Ease in) {
        return t -> 1 - in.apply(1 - t);
    }

    /** First half uses {@code in}, second half its mirrored "out" counterpart. */
    static Ease inOut(Ease in) {
        return t -> t < 0.5 ? in.apply(t * 2) / 2 : 1 - in.apply((1 - t) * 2) / 2;
    }

    default Ease reversed() {
        return t -> 1 - apply(1 - t);
    }

    static Ease of(DoubleUnaryOperator function) {
        return function::applyAsDouble;
    }

    // ---- standard curves ----------------------------------------------------------------------

    Ease LINEAR = t -> t;

    /** {@code t^p}; GSAP's power1..4 are p = 2..5. */
    static Ease powerIn(double p) {
        return t -> Math.pow(t, p);
    }

    Ease QUAD_IN = powerIn(2), QUAD_OUT = out(QUAD_IN), QUAD_IN_OUT = inOut(QUAD_IN);
    Ease CUBIC_IN = powerIn(3), CUBIC_OUT = out(CUBIC_IN), CUBIC_IN_OUT = inOut(CUBIC_IN);
    Ease QUART_IN = powerIn(4), QUART_OUT = out(QUART_IN), QUART_IN_OUT = inOut(QUART_IN);
    Ease QUINT_IN = powerIn(5), QUINT_OUT = out(QUINT_IN), QUINT_IN_OUT = inOut(QUINT_IN);

    Ease SINE_IN = t -> 1 - Math.cos(t * Math.PI / 2);
    Ease SINE_OUT = out(SINE_IN);
    Ease SINE_IN_OUT = t -> -(Math.cos(Math.PI * t) - 1) / 2;

    Ease EXPO_IN = t -> t == 0 ? 0 : Math.pow(2, 10 * t - 10);
    Ease EXPO_OUT = out(EXPO_IN);
    Ease EXPO_IN_OUT = inOut(EXPO_IN);

    Ease CIRC_IN = t -> 1 - Math.sqrt(1 - Math.min(1, t * t));
    Ease CIRC_OUT = out(CIRC_IN);
    Ease CIRC_IN_OUT = inOut(CIRC_IN);

    /** Pulls back before moving; {@code overshoot} 1.70158 matches GSAP's default. */
    static Ease backIn(double overshoot) {
        return t -> (overshoot + 1) * t * t * t - overshoot * t * t;
    }

    Ease BACK_IN = backIn(1.70158), BACK_OUT = out(BACK_IN), BACK_IN_OUT = inOut(backIn(1.70158 * 1.525));

    /**
     * Elastic oscillation that settles at the end.
     *
     * @param amplitude ≥ 1, how far it swings
     * @param period    length of one oscillation (0.3 = GSAP default)
     */
    static Ease elasticOut(double amplitude, double period) {
        double a = Math.max(1, amplitude);
        double s = period / (2 * Math.PI) * Math.asin(1 / a);
        return t -> t == 0 || t == 1 ? t : a * Math.pow(2, -10 * t) * Math.sin((t - s) * (2 * Math.PI) / period) + 1;
    }

    Ease ELASTIC_OUT = elasticOut(1, 0.3);
    Ease ELASTIC_IN = out(ELASTIC_OUT);
    Ease ELASTIC_IN_OUT = inOut(ELASTIC_IN);

    Ease BOUNCE_OUT = t -> {
        double n = 7.5625, d = 2.75;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) return n * (t -= 1.5 / d) * t + 0.75;
        if (t < 2.5 / d) return n * (t -= 2.25 / d) * t + 0.9375;
        return n * (t -= 2.625 / d) * t + 0.984375;
    };
    Ease BOUNCE_IN = out(BOUNCE_OUT);
    Ease BOUNCE_IN_OUT = inOut(BOUNCE_IN);

    /** Jumps in {@code count} discrete steps. */
    static Ease steps(int count) {
        int n = Math.max(1, count);
        return t -> t >= 1 ? 1 : Math.floor(t * n) / n;
    }

    /** CSS-style {@code cubic-bezier(x1, y1, x2, y2)}. */
    static Ease cubicBezier(double x1, double y1, double x2, double y2) {
        return t -> {
            if (t <= 0 || t >= 1) return t;
            // solve bezierX(u) = t for u (Newton, then bisection as a fallback)
            double u = t;
            for (int i = 0; i < 8; i++) {
                double x = bezier(u, x1, x2) - t;
                if (Math.abs(x) < 1e-6) return bezier(u, y1, y2);
                double dx = bezierSlope(u, x1, x2);
                if (Math.abs(dx) < 1e-6) break;
                u -= x / dx;
            }
            double lo = 0, hi = 1;
            u = t;
            for (int i = 0; i < 30; i++) {
                double x = bezier(u, x1, x2);
                if (Math.abs(x - t) < 1e-6) break;
                if (x < t) lo = u;
                else hi = u;
                u = (lo + hi) / 2;
            }
            return bezier(u, y1, y2);
        };
    }

    private static double bezier(double u, double p1, double p2) {
        double inv = 1 - u;
        return 3 * inv * inv * u * p1 + 3 * inv * u * u * p2 + u * u * u;
    }

    private static double bezierSlope(double u, double p1, double p2) {
        double inv = 1 - u;
        return 3 * inv * inv * p1 + 6 * inv * u * (p2 - p1) + 3 * u * u * (1 - p2);
    }

    // ---- GSAP string syntax -------------------------------------------------------------------

    /**
     * Parses GSAP ease strings: {@code none|linear}, {@code power0..4}, {@code quad|cubic|quart|quint|strong},
     * {@code sine|expo|circ|back|elastic|bounce} with {@code .in|.out|.inOut} (default {@code .out}),
     * optional parameters {@code back.out(1.7)}, {@code elastic.out(1, 0.3)}, and {@code steps(n)}.
     */
    static Ease parse(String spec) {
        String s = spec.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        double[] args = new double[0];
        int paren = s.indexOf('(');
        if (paren >= 0) {
            String inner = s.substring(paren + 1, s.lastIndexOf(')'));
            String[] parts = inner.isEmpty() ? new String[0] : inner.split(",");
            args = new double[parts.length];
            for (int i = 0; i < parts.length; i++) args[i] = Double.parseDouble(parts[i]);
            s = s.substring(0, paren);
        }
        if (s.equals("none") || s.equals("linear") || s.equals("power0")) return LINEAR;
        if (s.equals("steps")) return steps(args.length > 0 ? (int) args[0] : 1);
        String name = s;
        String type = "out";
        int dot = s.indexOf('.');
        if (dot >= 0) {
            name = s.substring(0, dot);
            type = s.substring(dot + 1);
        }
        Ease in = switch (name) {
            case "power1", "quad" -> QUAD_IN;
            case "power2", "cubic" -> CUBIC_IN;
            case "power3", "quart" -> QUART_IN;
            case "power4", "quint", "strong" -> QUINT_IN;
            case "sine" -> SINE_IN;
            case "expo" -> EXPO_IN;
            case "circ" -> CIRC_IN;
            case "back" -> backIn(args.length > 0 ? args[0] : 1.70158);
            case "elastic" -> out(elasticOut(args.length > 0 ? args[0] : 1, args.length > 1 ? args[1] : 0.3));
            case "bounce" -> BOUNCE_IN;
            default -> throw new IllegalArgumentException("Unknown ease: " + spec);
        };
        return switch (type) {
            case "in" -> in;
            case "inout" -> inOut(in);
            case "out" -> out(in);
            default -> throw new IllegalArgumentException("Unknown ease type: " + spec);
        };
    }
}
