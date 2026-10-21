package com.blockland.anim;

/**
 * Entry point of the animation library (a small GSAP for Java). Everything created here starts playing on
 * the next {@link Ticker#tick()}:
 * <pre>{@code
 * Anim.to(card.lift, 4, 0.25).ease("back.out(2)");
 * Anim.timeline().defaults(0.4, Ease.EXPO_OUT)
 *     .from(panel.alpha, 0, 0.3, 0)
 *     .from(panel.y, 24, 0.5, "<0.1")
 *     .call(() -> ready = true, ">");
 * Stagger.of(cards, 0.04, (card, i) -> Anim.from(card.offset, 30, 0.45).ease(Ease.QUART_OUT));
 * }</pre>
 */
public final class Anim {
    private Anim() {
    }

    public static Tween to(FloatProperty property, float value, double duration) {
        return schedule(new Tween(duration).to(property, value));
    }

    public static Tween from(FloatProperty property, float value, double duration) {
        return schedule(new Tween(duration).from(property, value));
    }

    public static Tween fromTo(FloatProperty property, float from, float to, double duration) {
        return schedule(new Tween(duration).fromTo(property, from, to));
    }

    public static Timeline timeline() {
        Timeline timeline = new Timeline();
        Ticker.global().add(timeline);
        return timeline;
    }

    /** Runs {@code callback} after {@code seconds} (killable like any animation). */
    public static Tween delayedCall(double seconds, Runnable callback) {
        return schedule(new Tween(0).delay(seconds).onComplete(callback));
    }

    public static void killTweensOf(AnimValue... values) {
        for (AnimValue value : values) value.kill();
    }

    private static Tween schedule(Tween tween) {
        Ticker.global().add(tween);
        return tween;
    }
}
