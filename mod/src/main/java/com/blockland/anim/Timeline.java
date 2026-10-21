package com.blockland.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sequences animations. Children are positioned with GSAP position parameters:
 * a number (absolute seconds), {@code "+=0.2"}/{@code "-=0.2"} (relative to the end of the timeline),
 * {@code "<"}/{@code ">"} (start/end of the previously added child, optionally with an offset such as
 * {@code "<0.1"} or {@code ">-0.05"}), or a label name with an optional offset ({@code "intro+=0.3"}).
 * Without a position a child is appended at the end.
 */
public final class Timeline extends Animation<Timeline> {
    private final List<Animation<?>> children = new ArrayList<>();
    private final Map<String, Double> labels = new HashMap<>();
    private Animation<?> previous;
    private double defaultDuration = 0.5;
    private Ease defaultEase = Ease.QUAD_OUT;

    Timeline() {
    }

    /** Duration and ease used by {@link #to}/{@link #from}/{@link #fromTo} on this timeline. */
    public Timeline defaults(double duration, Ease ease) {
        this.defaultDuration = duration;
        this.defaultEase = ease;
        return this;
    }

    public Timeline add(Animation<?> child) {
        return add(child, null);
    }

    public Timeline add(Animation<?> child, Object position) {
        Ticker.global().remove(child);
        if (child.parent != null && child.parent != this) child.parent.remove(child);
        double at = resolve(position);
        child.parent = this;
        child.startTime = at + child.delay;
        children.remove(child);
        int index = 0;
        while (index < children.size() && children.get(index).startTime <= child.startTime) index++;
        children.add(index, child);
        previous = child;
        return this;
    }

    public Timeline to(FloatProperty property, float value) {
        return to(property, value, defaultDuration, null);
    }

    public Timeline to(FloatProperty property, float value, double duration, Object position) {
        return add(new Tween(duration).ease(defaultEase).to(property, value), position);
    }

    public Timeline from(FloatProperty property, float value, double duration, Object position) {
        return add(new Tween(duration).ease(defaultEase).from(property, value), position);
    }

    public Timeline fromTo(FloatProperty property, float from, float to, double duration, Object position) {
        return add(new Tween(duration).ease(defaultEase).fromTo(property, from, to), position);
    }

    /** Runs {@code callback} when the playhead passes {@code position}. */
    public Timeline call(Runnable callback, Object position) {
        return add(new Tween(0).onComplete(callback), position);
    }

    public Timeline addLabel(String name, Object position) {
        labels.put(name, resolve(position));
        return this;
    }

    void remove(Animation<?> child) {
        if (children.remove(child)) child.parent = null;
    }

    @Override
    public double duration() {
        double end = 0;
        for (Animation<?> child : children) {
            double total = child.totalDuration();
            if (Double.isInfinite(total)) total = child.duration();
            end = Math.max(end, child.startTime + total);
        }
        return end;
    }

    @Override
    protected void renderLocal(double localTime, boolean forward, boolean suppressEvents) {
        // render in playback order so overlapping children leave the right final values
        List<Animation<?>> snapshot = new ArrayList<>(children);
        if (forward) {
            for (Animation<?> child : snapshot) child.render(localTime - child.startTime, suppressEvents);
        } else {
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                Animation<?> child = snapshot.get(i);
                child.render(localTime - child.startTime, suppressEvents);
            }
        }
    }

    @Override
    protected void onKill() {
        for (Animation<?> child : new ArrayList<>(children)) child.kill();
    }

    private double resolve(Object position) {
        double end = duration();
        if (position == null) return end;
        if (position instanceof Number number) return number.doubleValue();
        String p = position.toString().trim();
        if (p.startsWith("+=")) return end + Double.parseDouble(p.substring(2));
        if (p.startsWith("-=")) return Math.max(0, end - Double.parseDouble(p.substring(2)));
        if (p.startsWith("<") || p.startsWith(">")) {
            double base = previous == null ? 0 : p.startsWith("<") ? previous.startTime
                    : previous.startTime + finite(previous.totalDuration(), previous.duration());
            String offset = p.substring(1);
            return Math.max(0, base + (offset.isEmpty() ? 0 : Double.parseDouble(offset.replace("+", ""))));
        }
        int plus = p.indexOf("+=");
        int minus = p.indexOf("-=");
        String label = plus >= 0 ? p.substring(0, plus) : minus >= 0 ? p.substring(0, minus) : p;
        Double at = labels.get(label);
        if (at == null) throw new IllegalArgumentException("Unknown timeline label or position: " + p);
        if (plus >= 0) return at + Double.parseDouble(p.substring(plus + 2));
        if (minus >= 0) return Math.max(0, at - Double.parseDouble(p.substring(minus + 2)));
        return at;
    }

    private static double finite(double value, double fallback) {
        return Double.isInfinite(value) ? fallback : value;
    }
}
