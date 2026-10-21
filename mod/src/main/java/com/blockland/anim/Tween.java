package com.blockland.anim;

import java.util.ArrayList;
import java.util.List;

/**
 * Animates one or more properties over time. Built with {@link Anim#to}, {@link Anim#from}, {@link Anim#fromTo}
 * or {@link Timeline#to}; further properties can be chained with {@link #to(FloatProperty, float)} etc.
 * The default ease is {@link Ease#QUAD_OUT} (GSAP's {@code power1.out}).
 */
public final class Tween extends Animation<Tween> {
    private final double duration;
    private Ease ease = Ease.QUAD_OUT;
    private final List<Prop> props = new ArrayList<>();

    private static final class Prop {
        final FloatProperty target;
        float from;
        final float to;
        /** {@code from} is captured when the tween starts (to-tweens). */
        final boolean lazyFrom;
        boolean released;

        Prop(FloatProperty target, float from, float to, boolean lazyFrom) {
            this.target = target;
            this.from = from;
            this.to = to;
            this.lazyFrom = lazyFrom;
        }
    }

    Tween(double duration) {
        this.duration = Math.max(0, duration);
    }

    public Tween ease(Ease ease) {
        this.ease = ease == null ? Ease.LINEAR : ease;
        return this;
    }

    /** GSAP syntax, e.g. {@code "power3.out"}, {@code "back.out(1.7)"}. */
    public Tween ease(String spec) {
        return ease(Ease.parse(spec));
    }

    /** Animates {@code property} from its value at start time to {@code value}. */
    public Tween to(FloatProperty property, float value) {
        props.add(new Prop(property, 0, value, true));
        return this;
    }

    /** Animates {@code property} from {@code value} to its current value; applies {@code value} immediately. */
    public Tween from(FloatProperty property, float value) {
        float end = property.get();
        if (property instanceof AnimValue animValue) claim(animValue);
        property.set(value);
        props.add(new Prop(property, value, end, false));
        return this;
    }

    public Tween fromTo(FloatProperty property, float from, float to) {
        props.add(new Prop(property, from, to, false));
        return this;
    }

    @Override
    public double duration() {
        return duration;
    }

    @Override
    protected void init() {
        for (Prop prop : props) {
            if (prop.target instanceof AnimValue animValue) claim(animValue);
            if (prop.lazyFrom) prop.from = prop.target.get();
        }
    }

    @Override
    protected void renderLocal(double localTime, boolean forward, boolean suppressEvents) {
        double linear = duration <= 0 ? 1 : Math.max(0, Math.min(1, localTime / duration));
        double ratio = linear == 1 && duration <= 0 ? 1 : ease.apply(linear);
        for (Prop prop : props) {
            if (!prop.released) prop.target.set((float) (prop.from + (prop.to - prop.from) * ratio));
        }
    }

    /** The destination of {@code value} in this tween, or NaN if this tween does not animate it. */
    float targetOf(AnimValue value) {
        for (Prop prop : props) if (prop.target == value && !prop.released) return prop.to;
        return Float.NaN;
    }

    /** Another tween took over {@code value} ("overwrite: auto"). */
    void release(AnimValue value) {
        boolean anyLeft = false;
        for (Prop prop : props) {
            if (prop.target == value) prop.released = true;
            else if (!prop.released) anyLeft = true;
        }
        if (!anyLeft && !props.isEmpty()) killed = true;
    }

    private void claim(AnimValue value) {
        if (value.owner != null && value.owner != this) value.owner.release(value);
        value.owner = this;
    }

    @Override
    protected void onKill() {
        for (Prop prop : props) {
            if (prop.target instanceof AnimValue animValue && animValue.owner == this) animValue.owner = null;
        }
    }
}
