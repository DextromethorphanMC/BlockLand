package com.blockland.client.perf.terrain;

import java.util.List;
import java.util.function.Predicate;

/**
 * The section compile queue ({@code CompileTaskDynamicQueue.poll}) without its quadratic removal: vanilla's scan drops
 * each cancelled task with {@code ListIterator.remove()} on an array list, shifting the rest every time — thousands of
 * shifts per poll after a map switch or a fast flight cancels whole rows of sections. Here the cancelled tasks go in one
 * compacting pass first, keeping the order of the others; vanilla's scan then runs as it is over the same tasks.
 * <p>
 * Exactness. Cancellation only ever goes from false to true, so a task this pass keeps and vanilla's scan reads a moment
 * later is removed or kept exactly as a scan at that later moment would decide; the survivors have the same order and
 * so the same indices, distances and tie-breaks, and the queue's {@code size} counter is not touched (vanilla does not
 * count cancelled removals either). The whole poll stays inside the queue's monitor.
 */
public final class CompileQueue {
    private CompileQueue() {
    }

    /** Removes the cancelled tasks in one pass (order of the rest kept). */
    public static <T> void sweep(List<T> tasks, Predicate<? super T> cancelled) {
        int n = tasks.size();
        int w = 0;
        for (int r = 0; r < n; r++) {
            T task = tasks.get(r);
            if (cancelled.test(task)) continue;
            if (w != r) tasks.set(w, task);
            w++;
        }
        if (w < n) tasks.subList(w, n).clear();
    }
}
