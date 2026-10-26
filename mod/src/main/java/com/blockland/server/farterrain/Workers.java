package com.blockland.server.farterrain;

import com.blockland.BlockLand;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The far terrain's worker threads (daemon, below normal priority): captured chunks are merged first, then tiles in the
 * order their players need them, then imports of saved chunks, then disk writes. Never the server thread's work.
 */
final class Workers {
    static final int MERGE = 0, TILE = 1, IMPORT = 2, WRITE = 3;

    private final ThreadPoolExecutor pool;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger queued = new AtomicInteger();

    Workers(int threads) {
        // 0: half the cores (at least 2, at most 8), the other half stay with the game and its world generation
        int n = threads > 0 ? threads : Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "BlockLand far terrain " + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        };
        pool = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), factory);
        pool.allowCoreThreadTimeOut(true);
    }

    /** Runs {@code task} on a worker; lower bands first, then lower {@code order}, then first come. */
    void run(int band, double order, Runnable task) {
        if (pool.isShutdown()) return;
        queued.incrementAndGet();
        pool.execute(new Task(band, order, sequence.getAndIncrement(), () -> {
            queued.decrementAndGet();
            try {
                task.run();
            } catch (Throwable t) {
                BlockLand.LOGGER.warn("[BlockLand] Far terrain task failed", t);
            }
        }));
    }

    /** Tasks waiting or running. */
    int pending() {
        return queued.get();
    }

    int threads() {
        return pool.getCorePoolSize();
    }

    /** Drops what waits, lets running tasks finish (up to a few seconds). */
    void shutdown() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Task(int band, double order, long seq, Runnable body) implements Runnable, Comparable<Task> {
        @Override
        public void run() {
            body.run();
        }

        @Override
        public int compareTo(Task o) {
            if (band != o.band) return Integer.compare(band, o.band);
            if (order != o.order) return Double.compare(order, o.order);
            return Long.compare(seq, o.seq);
        }
    }
}
