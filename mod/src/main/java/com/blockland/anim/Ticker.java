package com.blockland.anim;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives all standalone animations and springs. Call {@link #tick()} once per rendered frame (calling it several
 * times per frame is harmless). Long frames are smoothed like GSAP's lagSmoothing: a hitch never makes an
 * animation jump ahead by more than one 60 fps frame.
 */
public final class Ticker {
    /** Something updated every frame until it reports it is done (e.g. a {@link Spring} at rest). */
    public interface Updatable {
        /** @return false when it no longer needs updates */
        boolean update(double dt);
    }

    private static final Ticker GLOBAL = new Ticker();
    /** Where time comes from: the real clock, or the client's render clock (which the benchmark can hold). */
    private static java.util.function.LongSupplier clock = System::nanoTime;
    private static final double LAG_THRESHOLD = 0.1;
    private static final double LAG_STEP = 1.0 / 60.0;

    private final List<Animation<?>> animations = new ArrayList<>();
    private final List<Updatable> updatables = new ArrayList<>();
    private long lastNanos = -1;
    private double time;
    private double timeScale = 1;

    public static Ticker global() {
        return GLOBAL;
    }

    /** The client hands its render clock over at start-up. */
    public static void setClock(java.util.function.LongSupplier nanos) {
        clock = nanos;
    }

    /** Advances everything by the real time elapsed since the previous call. */
    public void tick() {
        long now = clock.getAsLong();
        if (lastNanos < 0) {
            lastNanos = now;
            return;
        }
        double dt = (now - lastNanos) / 1e9;
        lastNanos = now;
        if (dt <= 0) return;
        if (dt > LAG_THRESHOLD) dt = LAG_STEP;
        update(dt * timeScale);
    }

    /** Advances everything by {@code dt} seconds (tests or manual stepping). */
    public void update(double dt) {
        time += dt;
        for (Animation<?> animation : new ArrayList<>(animations)) {
            if (!animation.scheduled) continue;
            if (!animation.advance(dt)) remove(animation);
        }
        for (Updatable updatable : new ArrayList<>(updatables)) {
            if (!updatable.update(dt)) updatables.remove(updatable);
        }
    }

    /** Seconds of (scaled) time since the ticker started. */
    public double time() {
        return time;
    }

    /** Global speed multiplier (e.g. 0.25 to inspect animations in slow motion). */
    public void timeScale(double scale) {
        this.timeScale = Math.max(0, scale);
    }

    void add(Animation<?> animation) {
        if (animation.parent != null || animation.scheduled) return;
        animation.scheduled = true;
        animations.add(animation);
    }

    void remove(Animation<?> animation) {
        if (animations.remove(animation)) animation.scheduled = false;
    }

    public void schedule(Updatable updatable) {
        if (!updatables.contains(updatable)) updatables.add(updatable);
    }
}
