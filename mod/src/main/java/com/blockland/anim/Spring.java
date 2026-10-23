package com.blockland.anim;

/**
 * A damped spring that follows a moving target: ideal for hover/press feedback that must react instantly
 * and smoothly to input changing mid-animation. Updated by the {@link Ticker} only while not at rest.
 * Defaults (stiffness 170, damping 26) match react-spring's "default" config.
 */
public final class Spring implements FloatProperty, Ticker.Updatable {
    private static final double STEP = 1.0 / 240.0;

    private double value;
    private double target;
    private double velocity;
    private double stiffness = 170;
    private double damping = 26;
    private double mass = 1;
    private boolean scheduled;

    public Spring(float initial) {
        this.value = initial;
        this.target = initial;
    }

    /** Stiffer = faster; less damping = more overshoot/wobble. */
    public Spring config(double stiffness, double damping) {
        this.stiffness = stiffness;
        this.damping = damping;
        return this;
    }

    public Spring mass(double mass) {
        this.mass = Math.max(0.01, mass);
        return this;
    }

    public void setTarget(float target) {
        if (this.target == target) return;
        this.target = target;
        wake();
    }

    /** Kicks the spring (e.g. a "pop" on click) without moving its target. */
    public void impulse(float velocity) {
        this.velocity += velocity;
        wake();
    }

    /**
     * Kicks the spring so that, from rest, its first swing peaks about {@code peak} away from the target (units of the
     * value): the impulse is sized from the stiffness, damping and mass, so retuning a spring keeps the kick's size.
     */
    public void punch(float peak) {
        double omega = Math.sqrt(stiffness / mass);
        double zeta = damping / (2 * Math.sqrt(stiffness * mass));
        double first = zeta < 0.999
                ? Math.exp(-zeta / Math.sqrt(1 - zeta * zeta) * Math.atan2(Math.sqrt(1 - zeta * zeta), zeta))
                : Math.exp(-1);
        impulse((float) (peak * omega / first));
    }

    public float target() {
        return (float) target;
    }

    @Override
    public float get() {
        return (float) value;
    }

    /** Snaps value and target. */
    @Override
    public void set(float value) {
        this.value = value;
        this.target = value;
        this.velocity = 0;
    }

    public boolean isAtRest() {
        return !scheduled;
    }

    @Override
    public boolean update(double dt) {
        double remaining = dt;
        while (remaining > 1e-9) {
            double h = Math.min(STEP, remaining);
            double acceleration = (-stiffness * (value - target) - damping * velocity) / mass;
            velocity += acceleration * h;
            value += velocity * h;
            remaining -= h;
        }
        if (Math.abs(velocity) < 1e-3 && Math.abs(value - target) < 5e-4) {
            value = target;
            velocity = 0;
            scheduled = false;
            return false;
        }
        return true;
    }

    private void wake() {
        if (!scheduled) {
            scheduled = true;
            Ticker.global().schedule(this);
        }
    }
}
