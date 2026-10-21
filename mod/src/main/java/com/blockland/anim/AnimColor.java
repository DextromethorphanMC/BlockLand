package com.blockland.anim;

/** An animatable ARGB colour; transitions are mixed perceptually ({@link ColorMath#mix}). */
public final class AnimColor {
    private final AnimValue progress = new AnimValue(1);
    private int from;
    private int to;

    public AnimColor(int initial) {
        this.from = initial;
        this.to = initial;
    }

    public int get() {
        return ColorMath.mix(from, to, progress.get());
    }

    /** Transitions from the current colour to {@code target}. */
    public Tween to(int target, double duration, Ease ease) {
        from = get();
        to = target;
        progress.snap(0);
        return progress.to(1, duration, ease);
    }

    /** Only starts a transition when {@code target} is not already the destination. */
    public void toIfChanged(int target, double duration, Ease ease) {
        if (to != target) to(target, duration, ease);
    }

    public void snap(int color) {
        progress.snap(1);
        from = color;
        to = color;
    }

    public int target() {
        return to;
    }
}
