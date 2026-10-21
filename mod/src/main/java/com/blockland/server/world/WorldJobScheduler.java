package com.blockland.server.world;

import com.blockland.BlockLand;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/** Runs {@link WorldJob}s on the server thread with a shared per-tick block budget. */
public final class WorldJobScheduler {
    private final Deque<Entry> jobs = new ArrayDeque<>();
    private final IntSupplier budgetPerTick;

    public WorldJobScheduler(IntSupplier budgetPerTick) {
        this.budgetPerTick = budgetPerTick;
    }

    public CompletableFuture<Void> submit(WorldJob job) {
        Entry entry = new Entry(job, new CompletableFuture<>());
        jobs.add(entry);
        return entry.future;
    }

    public void tick() {
        int budget = budgetPerTick.getAsInt();
        while (budget > 0 && !jobs.isEmpty()) {
            Entry entry = jobs.peek();
            try {
                budget -= Math.max(1, entry.job.step(budget));
                if (entry.job.isDone()) {
                    jobs.poll();
                    entry.future.complete(null);
                }
            } catch (Exception e) {
                BlockLand.LOGGER.error("[BlockLand] World job failed", e);
                jobs.poll();
                entry.future.completeExceptionally(e);
            }
        }
    }

    /** Finishes all pending jobs immediately (used on shutdown). */
    public void drain() {
        while (!jobs.isEmpty()) {
            Entry entry = jobs.poll();
            try {
                while (!entry.job.isDone()) entry.job.step(Integer.MAX_VALUE);
                entry.future.complete(null);
            } catch (Exception e) {
                entry.future.completeExceptionally(e);
            }
        }
    }

    private record Entry(WorldJob job, CompletableFuture<Void> future) {
    }
}
