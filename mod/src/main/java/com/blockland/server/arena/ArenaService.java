package com.blockland.server.arena;

import com.blockland.BlockLand;
import com.blockland.server.world.ClearJob;
import com.blockland.server.world.WorldJobScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/**
 * Hands out isolated regions ("slots") of the void arena dimension, one per running match,
 * and wipes them when the match is over.
 */
public final class ArenaService {
    public static final ResourceKey<Level> ARENA_DIMENSION = ResourceKey.create(Registries.DIMENSION, BlockLand.id("arena"));
    private static final int CLEANUP_MARGIN = 24;

    private final MinecraftServer server;
    private final WorldJobScheduler jobs;
    private final IntSupplier spacing;
    private final IntSupplier baseY;
    private final BitSet usedSlots = new BitSet();

    public ArenaService(MinecraftServer server, WorldJobScheduler jobs, IntSupplier spacing, IntSupplier baseY) {
        this.server = server;
        this.jobs = jobs;
        this.spacing = spacing;
        this.baseY = baseY;
    }

    public ServerLevel level() {
        ServerLevel level = server.getLevel(ARENA_DIMENSION);
        if (level == null) throw new IllegalStateException("Arena dimension " + ARENA_DIMENSION.location() + " is not loaded");
        return level;
    }

    public Arena allocate(Vec3i size) {
        ServerLevel level = level();
        int slot = usedSlots.nextClearBit(0);
        usedSlots.set(slot);
        int y = Math.max(level.getMinY() + CLEANUP_MARGIN, Math.min(baseY.getAsInt(), level.getMaxY() - size.getY() - CLEANUP_MARGIN));
        // slot 0 starts one spacing away from the dimension origin
        BlockPos origin = new BlockPos((slot + 1) * spacing.getAsInt(), y, 0);
        return new Arena(slot, level, origin, size);
    }

    /** Wipes the arena region; the slot becomes available again once the cleanup finished. */
    public CompletableFuture<Void> release(Arena arena) {
        return jobs.submit(new ClearJob(arena.level(), arena.cleanupBox(CLEANUP_MARGIN)))
                .whenComplete((ignored, error) -> usedSlots.clear(arena.slot()));
    }

    public int activeSlots() {
        return usedSlots.cardinality();
    }
}
