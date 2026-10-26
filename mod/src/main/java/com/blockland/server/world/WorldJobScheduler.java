package com.blockland.server.world;

import com.blockland.BlockLand;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/** Runs {@link WorldJob}s on the server thread with a shared per-tick block budget. */
public final class WorldJobScheduler {
    private final Deque<Entry> jobs = new ArrayDeque<>();
    private final IntSupplier budgetPerTick;
    private final List<BiConsumer<ServerLevel, BoundingBox>> completionListeners = new ArrayList<>();

    public WorldJobScheduler(IntSupplier budgetPerTick) {
        this.budgetPerTick = budgetPerTick;
    }

    /** Told the level and box of every job that finished (or failed part way: its box changed either way). */
    public void addCompletionListener(BiConsumer<ServerLevel, BoundingBox> listener) {
        completionListeners.add(listener);
    }

    private void finished(WorldJob job) {
        ServerLevel level = job.level();
        BoundingBox area = job.area();
        if (level == null || area == null) return;
        for (BiConsumer<ServerLevel, BoundingBox> listener : completionListeners) {
            try {
                listener.accept(level, area);
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] World job listener failed", e);
            }
        }
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
                    finished(entry.job);
                    entry.future.complete(null);
                }
            } catch (Exception e) {
                BlockLand.LOGGER.error("[BlockLand] World job failed", e);
                jobs.poll();
                finished(entry.job);
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
                finished(entry.job);
                entry.future.complete(null);
            } catch (Exception e) {
                entry.future.completeExceptionally(e);
            }
        }
    }

    private record Entry(WorldJob job, CompletableFuture<Void> future) {
    }
}
