package com.blockland.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

/** GSAP-style staggers: one animation per item, offset in time according to the item's position. */
public final class Stagger {
    /** Where the wave starts. */
    public enum From { START, END, CENTER, EDGES, RANDOM }

    private Stagger() {
    }

    /**
     * @param each    seconds between consecutive items
     * @param factory builds the animation for (item, index)
     * @return a timeline containing every item's animation (already playing)
     */
    public static <T> Timeline of(List<T> items, double each, From from, BiFunction<T, Integer, Animation<?>> factory) {
        Timeline timeline = Anim.timeline();
        int n = items.size();
        List<Integer> randomOrder = new ArrayList<>();
        for (int i = 0; i < n; i++) randomOrder.add(i);
        if (from == From.RANDOM) Collections.shuffle(randomOrder, new Random());
        double center = (n - 1) / 2.0;
        for (int i = 0; i < n; i++) {
            double slot = switch (from) {
                case START -> i;
                case END -> n - 1 - i;
                case CENTER -> Math.abs(i - center);
                case EDGES -> center - Math.abs(i - center);
                case RANDOM -> randomOrder.get(i);
            };
            timeline.add(factory.apply(items.get(i), i), slot * each);
        }
        return timeline;
    }

    public static <T> Timeline of(List<T> items, double each, BiFunction<T, Integer, Animation<?>> factory) {
        return of(items, each, From.START, factory);
    }
}
