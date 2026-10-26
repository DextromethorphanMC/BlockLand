package com.blockland.client.graphics.far;

import com.blockland.BlockLand;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The far terrain's background threads (rendering spec §22): decoding tiles, the disk cache and meshing, most urgent first
 * (lower priority value first, then oldest). Low priority daemons, so they never hold the game back or keep it alive.
 * Results go back to the render thread through queues; nothing here touches the game's state.
 */
final class FarWorkers {
    private static final AtomicLong order = new AtomicLong();
    private static final ThreadPoolExecutor pool;

    static {
        AtomicInteger count = new AtomicInteger();
        int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 4));
        pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "BlockLand far terrain " + count.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
    }

    private FarWorkers() {
    }

    /** Runs {@code work} on a worker; smaller {@code priority} first. */
    static void submit(double priority, Runnable work) {
        pool.execute(new Job(priority, order.getAndIncrement(), work));
    }

    /** Jobs waiting (not running). */
    static int backlog() {
        return pool.getQueue().size();
    }

    private record Job(double priority, long order, Runnable work) implements Runnable, Comparable<Job> {
        @Override
        public void run() {
            try {
                work.run();
            } catch (Throwable t) {
                BlockLand.LOGGER.warn("[BlockLand] Far terrain background job failed", t);
            }
        }

        @Override
        public int compareTo(Job other) {
            int c = Double.compare(priority, other.priority);
            return c != 0 ? c : Long.compare(order, other.order);
        }
    }
}
