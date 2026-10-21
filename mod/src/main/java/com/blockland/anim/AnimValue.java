package com.blockland.anim;

/**
 * An animatable float. Tweening it with {@link #to} automatically overwrites any tween already driving it
 * (GSAP's {@code overwrite: "auto"}), so interactive animations (hover in/out) never fight each other.
 */
public final class AnimValue implements FloatProperty {
    private float value;
    /** The tween currently driving this value, if any. */
    Tween owner;

    public AnimValue(float initial) {
        this.value = initial;
    }

    @Override
    public float get() {
        return value;
    }

    @Override
    public void set(float value) {
        this.value = value;
    }

    /** Sets the value immediately and stops any tween driving it. */
    public AnimValue snap(float value) {
        kill();
        this.value = value;
        return this;
    }

    /** Animates from the current value to {@code target}; starts on the next frame. */
    public Tween to(float target, double duration, Ease ease) {
        return Anim.to(this, target, duration).ease(ease);
    }

    /** Like {@link #to} but only when the target differs from the current destination. */
    public Tween toIfChanged(float target, double duration, Ease ease) {
        if (owner != null && owner.isActive() && owner.targetOf(this) == target) return owner;
        if (owner == null && value == target) return null;
        return to(target, duration, ease);
    }

    public boolean isAnimating() {
        return owner != null && owner.isActive();
    }

    public void kill() {
        if (owner != null) owner.release(this);
        owner = null;
    }
}
