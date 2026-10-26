package com.blockland.server.farterrain;

import com.blockland.network.packet.FarTerrainPackets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One player's far terrain session (rendering spec §22): the level they are in and its session number, their view limit,
 * the tiles they asked for and not yet got, the answers waiting for their byte budget, and which version of each tile
 * they hold (so a tile that changes later is pushed to them); the same for the real far chunks (§23). Server thread,
 * except {@link #outbox}, {@link #columnsOutbox} and {@link #inFlight}, which workers fill.
 */
final class Viewer {
    /** A tile asked for; {@code push}: not asked for, the server refreshes a tile the client holds. */
    record Pending(long key, long version, boolean push) {
    }

    /** An answer of a session; a push answer is dropped when the client already holds its version. */
    record Outgoing(int session, FarTerrainPackets.Answer answer, boolean push) {
    }

    final UUID id;
    int session;
    @Nullable
    ResourceKey<Level> dimension;
    @Nullable
    BoundingBox limit;
    int limitCheck;
    final Long2ObjectLinkedOpenHashMap<Pending> pending = new Long2ObjectLinkedOpenHashMap<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final ConcurrentLinkedQueue<Outgoing> outbox = new ConcurrentLinkedQueue<>();
    final Long2LongOpenHashMap held = new Long2LongOpenHashMap();
    double budget;

    // real far chunks (rendering spec §23): the same session, their own queue, answers, budget and held versions
    final Long2ObjectLinkedOpenHashMap<Pending> columnsPending = new Long2ObjectLinkedOpenHashMap<>();
    final ConcurrentLinkedQueue<Outgoing> columnsOutbox = new ConcurrentLinkedQueue<>();
    final Long2LongOpenHashMap columnsHeld = new Long2LongOpenHashMap();
    /** Units of this viewer being made right now, and of those the coarse ones (scale 2 and up). */
    int columnsInFlight, columnsInFlightCoarse;
    double columnBudget;
    /** Farthest chunk of the units asked for this stay (chunks from the player), for the background generation's reach. */
    int columnReach;
    /**
     * The waiting units nearest first as they were last put in order (where the player stood then, which tick), how far
     * the dispatch got through them, and whether units were asked for since.
     */
    @Nullable
    long[] columnsOrder;
    int columnsCursor, columnsSortTick, columnsSortX, columnsSortZ;
    boolean columnsFresh;

    Viewer(UUID id) {
        this.id = id;
    }

    /** A new stay: everything of the old one is forgotten (answers still on their way are dropped by session). */
    void reset(int session, ResourceKey<Level> dimension, @Nullable BoundingBox limit) {
        this.session = session;
        this.dimension = dimension;
        this.limit = limit;
        pending.clear();
        held.clear();
        outbox.clear();
        budget = 0;
        columnsPending.clear();
        columnsOutbox.clear();
        columnsHeld.clear();
        columnsInFlight = 0;
        columnsInFlightCoarse = 0;
        columnBudget = 0;
        columnReach = 0;
        columnsOrder = null;
        columnsCursor = 0;
        columnsFresh = false;
    }
}
