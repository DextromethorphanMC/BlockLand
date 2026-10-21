package com.blockland.anim;

/**
 * Base of {@link Tween} and {@link Timeline}: timing (delay, duration, repeat, repeatDelay, yoyo),
 * playback control (play, pause, reverse, restart, seek, progress, timeScale) and callbacks.
 * <p>
 * Standalone animations are driven by the {@link Ticker}; animations added to a {@link Timeline} are driven
 * by it instead (their own timeScale/reversed flags then do not apply, as in GSAP the parent owns time).
 */
public abstract class Animation<SELF extends Animation<SELF>> {
    protected double delay;
    protected int repeat;
    protected double repeatDelay;
    protected boolean yoyo;
    protected double timeScale = 1;
    protected boolean reversed;
    protected boolean paused;
    protected boolean killed;

    Timeline parent;
    /** True while the {@link Ticker} drives this animation. */
    boolean scheduled;
    /** Start position inside the parent timeline (includes this animation's delay). */
    double startTime;
    /** Playhead used when driven by the ticker (seconds since play, including the delay). */
    double rootTime;

    private double renderedTime = Double.NaN;
    private boolean started;
    private boolean completed;
    private int lastIteration = -1;

    private Runnable onStart, onUpdate, onComplete, onRepeat, onReverseComplete;

    // ---- definition ---------------------------------------------------------------------------

    /** Length of one iteration in seconds. */
    public abstract double duration();

    /** Called once when the animation first renders (e.g. tweens record their start values). */
    protected void init() {
    }

    /** Renders the state at {@code localTime} within one iteration ({@code 0..duration}). */
    protected abstract void renderLocal(double localTime, boolean forward, boolean suppressEvents);

    public double totalDuration() {
        if (repeat < 0) return Double.POSITIVE_INFINITY;
        return duration() * (repeat + 1) + repeatDelay * repeat;
    }

    // ---- fluent configuration ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    protected final SELF self() {
        return (SELF) this;
    }

    public SELF delay(double seconds) {
        this.delay = Math.max(0, seconds);
        return self();
    }

    /** Number of extra iterations; -1 repeats forever. */
    public SELF repeat(int count) {
        this.repeat = count;
        return self();
    }

    public SELF repeatDelay(double seconds) {
        this.repeatDelay = Math.max(0, seconds);
        return self();
    }

    /** Every other iteration plays backwards. */
    public SELF yoyo(boolean yoyo) {
        this.yoyo = yoyo;
        return self();
    }

    public SELF timeScale(double scale) {
        this.timeScale = Math.max(0, scale);
        return self();
    }

    public SELF onStart(Runnable callback) {
        this.onStart = callback;
        return self();
    }

    public SELF onUpdate(Runnable callback) {
        this.onUpdate = callback;
        return self();
    }

    public SELF onComplete(Runnable callback) {
        this.onComplete = callback;
        return self();
    }

    public SELF onRepeat(Runnable callback) {
        this.onRepeat = callback;
        return self();
    }

    public SELF onReverseComplete(Runnable callback) {
        this.onReverseComplete = callback;
        return self();
    }

    // ---- playback control (standalone animations) ----------------------------------------------

    public SELF play() {
        paused = false;
        reversed = false;
        if (completed && rootTime - delay >= totalDuration()) restartState();
        Ticker.global().add(this);
        return self();
    }

    public SELF pause() {
        paused = true;
        return self();
    }

    public SELF resume() {
        paused = false;
        Ticker.global().add(this);
        return self();
    }

    /** Plays backwards from the current position (towards the start). */
    public SELF reverse() {
        reversed = !reversed;
        paused = false;
        Ticker.global().add(this);
        return self();
    }

    public SELF restart() {
        restartState();
        paused = false;
        reversed = false;
        Ticker.global().add(this);
        return self();
    }

    /** Jumps to {@code seconds} after the delay and renders immediately. */
    public SELF seek(double seconds) {
        rootTime = seconds + delay;
        render(seconds, true);
        return self();
    }

    /** Jumps to a fraction of the total duration (0..1). */
    public SELF progress(double fraction) {
        double total = totalDuration();
        return seek(Double.isInfinite(total) ? duration() * fraction : total * fraction);
    }

    public double progress() {
        double total = totalDuration();
        if (Double.isNaN(renderedTime) || total <= 0) return renderedTime >= 0 ? 1 : 0;
        return Math.max(0, Math.min(1, renderedTime / total));
    }

    public void kill() {
        killed = true;
        if (parent != null) parent.remove(this);
        onKill();
    }

    /** Hook for releasing targets when killed. */
    protected void onKill() {
    }

    /** Scheduled (or inside a timeline) and not finished; true during the delay as well. */
    public boolean isActive() {
        return !killed && !completed && (scheduled || parent != null);
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isComplete() {
        return completed;
    }

    public boolean isReversed() {
        return reversed;
    }

    private void restartState() {
        rootTime = 0;
        renderedTime = Double.NaN;
        started = false;
        completed = false;
        lastIteration = -1;
    }

    // ---- rendering ----------------------------------------------------------------------------

    /**
     * Renders the animation at {@code totalTime} seconds after its (delayed) start.
     * Negative times mean "not started yet"; times past {@link #totalDuration()} are clamped.
     */
    final void render(double totalTime, boolean suppressEvents) {
        if (killed) return;
        double previous = renderedTime;
        renderedTime = totalTime;
        boolean forward = Double.isNaN(previous) || totalTime >= previous;

        if (totalTime < 0) {
            if (started) {
                // played backwards past the start: show the initial state once
                renderLocal(0, false, suppressEvents);
                started = false;
                completed = false;
                lastIteration = -1;
                if (!suppressEvents && onReverseComplete != null) onReverseComplete.run();
            }
            return;
        }
        if (!started) {
            started = true;
            init();
            if (!suppressEvents && onStart != null) onStart.run();
        }

        double duration = duration();
        double total = totalDuration();
        boolean done = totalTime >= total;
        double t = Math.min(totalTime, total);
        int iteration;
        double local;
        if (duration <= 0) {
            iteration = 0;
            local = 0;
        } else {
            double cycle = duration + repeatDelay;
            iteration = (int) Math.floor(t / cycle);
            local = t - iteration * cycle;
            if (local > duration) local = duration;
            if (done && repeat >= 0) {
                iteration = repeat;
                local = duration;
            }
            if (yoyo && (iteration & 1) == 1) local = duration - local;
        }

        renderLocal(local, yoyo && (iteration & 1) == 1 ? !forward : forward, suppressEvents);
        if (!suppressEvents) {
            if (lastIteration >= 0 && iteration != lastIteration && onRepeat != null) onRepeat.run();
            if (onUpdate != null) onUpdate.run();
        }
        lastIteration = iteration;

        if (done && !completed) {
            completed = true;
            if (!suppressEvents && onComplete != null) onComplete.run();
        } else if (!done) {
            completed = false;
        }
    }

    /** Advances a standalone animation; returns false once it no longer needs ticking. */
    final boolean advance(double dt) {
        if (killed) return false;
        if (paused) return true;
        rootTime += dt * timeScale * (reversed ? -1 : 1);
        double t = rootTime - delay;
        if (reversed && t <= 0) {
            rootTime = delay;
            render(0, false);
            render(-1e-9, false);
            return false;
        }
        render(t, false);
        return !(completed && !reversed);
    }
}
