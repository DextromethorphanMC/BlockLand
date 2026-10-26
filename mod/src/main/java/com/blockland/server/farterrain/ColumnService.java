package com.blockland.server.farterrain;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarColumn;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.FarChunkPackets;
import com.blockland.network.packet.FarTerrainPackets;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Real far chunks on the server (rendering spec §23): the units players ask for beyond their view distance. A unit of
 * scale 0 is a chunk as the client's own chunk packet would carry it (sections and their light), from full chunks only —
 * loaded ones read on the server thread within a budget per tick, saved ones read from the region files (older game
 * versions brought up to date) and parsed on the workers, nothing generated on request (the background generation makes
 * them). A unit of scale k ≥ 1 is built on the workers from its four children of scale k − 1 ({@link UnitBuilder}), so
 * it always stands for real chunks, and kept on disk ({@link UnitFiles}) with the stamps of what it was built from; a
 * child being made serves every parent and player waiting for it. Answers go out nearest first within each player's byte
 * budget; encoded units are kept in a bounded cache per level. When a chunk changes, its unit and every coarser one over
 * it are made again and pushed to whoever holds them: the chunk about two seconds after the change settles, the coarser
 * ones after half a minute of quiet (finer ones first). Server thread, except the worker jobs, whose results come back
 * through {@link #done}.
 */
final class ColumnService {
    /** Units kept asked per player (the farthest are dropped beyond), and asked units being made at once per player. */
    private static final int QUEUE = 16384, IN_FLIGHT = 64;
    /** Of {@link #IN_FLIGHT}, kept for units of scale 0 and 1 (coarse ones take long: near ones never wait behind them). */
    private static final int FINE_RESERVED = 16;
    /** Largest {@code S2CColumns} (bytes of answers). */
    private static final int PACKET_BYTES = 256 * 1024;
    /** Server-thread time per tick for reading loaded chunks, and (about) for taking in what the workers made. */
    private static final long CAPTURE_NANOS = 1_500_000, DONE_NANOS = 2_000_000;
    /** Saved chunks read at once per level (the chunk storage also serves the game's own loads). */
    private static final int READS_IN_FLIGHT = 24;
    /** Encoded units kept per level (bytes). */
    private static final long CACHE_BYTES = 256L << 20;
    /** Region tables kept per level for the stamps (each about 4 KB). */
    private static final int REGION_HEADERS = 4096;
    /** A changed chunk is re-sent once it has been quiet this long; coarser units after longer, or at the latest after. */
    private static final long PUSH_DELAY_MS = 2_000, COARSE_PUSH_DELAY_MS = 30_000, COARSE_PUSH_MAX_MS = 300_000;
    /** Ticks between looks at settled changes; between new orderings of a player's queue (sooner when it ran out). */
    private static final int PUSH_TICKS = 5, SORT_TICKS = 10;
    /** Queue entries looked at per player and tick, units started (or answered from the cache) at most. */
    private static final int WALK = 1024, STARTS = 256;
    /** The version EMPTY answers carry (none of the unit's chunks exists). */
    static final long EMPTY_VERSION = 1;

    /** An encoded unit, or none of it there ({@code bytes} null); {@code absent}: how many of its chunks do not exist. */
    record Encoded(long version, @Nullable byte[] bytes, int absent) {
        long size() {
            return 64 + (bytes == null ? 0 : bytes.length);
        }
    }

    /** A player waiting for a unit: their session then, the version they hold, whether it counts as a coarse one. */
    private record Waiter(Viewer viewer, int session, Viewer.Pending pending, boolean coarse) {
    }

    /** Work waiting for the chunk storage or the server thread, in the order of {@link UnitMath#rank}, then along a Z curve. */
    private record Queued(long key, int rank, long curve) implements Comparable<Queued> {
        @Override
        public int compareTo(Queued o) {
            if (rank != o.rank) return Integer.compare(rank, o.rank);
            return Long.compare(curve, o.curve);
        }
    }

    /** A unit being made: who waits for it (players, coarser units), and for scale ≥ 1 the children gathered so far. */
    private static final class Job {
        final long key;
        final int rank;
        final List<Waiter> waiters = new ArrayList<>(1);
        final List<Job> parents = new ArrayList<>(1);
        final LongArrayList slots = new LongArrayList(1);
        @Nullable
        Encoded[] children;
        int missing;
        @Nullable
        long[] stamps;
        /** A child could not be made (it is taken as not there, and what is built from it is neither cached nor kept). */
        boolean childFailed;

        Job(long key, int rank) {
            this.key = key;
            this.rank = rank;
        }
    }

    /** One level's units: the cache, the jobs running, what waits for the server thread or the chunk storage. */
    private static final class Dim {
        final ServerLevel level;
        final Registry<Biome> biomes;
        final RegionIndex regions;
        final UnitFiles files;
        final Long2ObjectLinkedOpenHashMap<Encoded> cache = new Long2ObjectLinkedOpenHashMap<>();
        long cacheBytes;
        final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
        final PriorityQueue<Queued> captures = new PriorityQueue<>();
        final PriorityQueue<Queued> reads = new PriorityQueue<>();
        int readsRunning;
        /** Units over changed chunks: when they changed last and first (pushes wait until they settle). */
        final Long2LongOpenHashMap changedLast = new Long2LongOpenHashMap(), changedFirst = new Long2LongOpenHashMap();
        /** Coarse units that settled after a change: made again from their children, never taken from disk. */
        final LongOpenHashSet dirty = new LongOpenHashSet();
        /** Per scale: units built, taken from disk. */
        final long[] built = new long[FarColumn.MAX_SCALE + 1], fromDisk = new long[FarColumn.MAX_SCALE + 1];

        Dim(ServerLevel level) {
            this.level = level;
            this.biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
            Path root = level.getServer().getWorldPath(LevelResource.ROOT);
            this.regions = new RegionIndex(DimensionType.getStorageFolder(level.dimension(), root).resolve("region"), true, REGION_HEADERS);
            this.files = new UnitFiles(root.resolve("blockland").resolve("farchunks").resolve(level.dimension().location().getNamespace())
                    .resolve(level.dimension().location().getPath()));
        }
    }

    private final MinecraftServer server;
    private final Supplier<Workers> workers;
    private final Map<ResourceKey<Level>, Dim> dims = new HashMap<>();
    /** Worker results, applied on the server thread at the next tick. */
    private final ConcurrentLinkedQueue<Runnable> done = new ConcurrentLinkedQueue<>();
    private int ticks;
    /** Chunks of the units answered to requests, and of those how many do not exist (not generated yet). */
    private long chunksAnswered, chunksAbsent;

    ColumnService(MinecraftServer server, Supplier<Workers> workers) {
        this.server = server;
        this.workers = workers;
    }

    private Dim dim(ServerLevel level) {
        return dims.computeIfAbsent(level.dimension(), k -> new Dim(level));
    }

    // ---- requests ---------------------------------------------------------------------------------------------------

    /** A request of a player in their current stay ({@code offered}: far terrain runs in this level). */
    void onRequest(ServerPlayer player, Viewer viewer, FarChunkPackets.C2SRequest request, boolean offered, FarTerrainSettings settings) {
        if (request.keys().length == 0) {
            viewer.columnsPending.clear();
            viewer.columnsOrder = null;
            return;
        }
        int pcx = player.getBlockX() >> 4, pcz = player.getBlockZ() >> 4;
        int reach = settings.columnDistance();
        for (int i = 0; i < request.keys().length; i++) {
            long key = request.keys()[i];
            if (!offered || !UnitMath.valid(key) || UnitMath.distance(pcx, pcz, key) > reach + 1 || !UnitMath.touches(viewer.limit, key)) {
                viewer.columnsOutbox.add(new Viewer.Outgoing(viewer.session, FarTerrainPackets.Answer.of(key, FarTerrainPackets.DENIED, 0), false));
                continue;
            }
            // the background generation follows the farthest chunk asked for
            viewer.columnReach = Math.max(viewer.columnReach, Math.min(UnitMath.farthest(pcx, pcz, key), reach));
            viewer.columnsPending.put(key, new Viewer.Pending(key, request.versions()[i], false));
            viewer.columnsFresh = true;
        }
        if (viewer.columnsPending.size() > QUEUE + QUEUE / 8) trim(viewer, pcx, pcz);
    }

    /** Too much asked at once: the farthest wait for the client to ask again. */
    private static void trim(Viewer viewer, int pcx, int pcz) {
        long[] keys = viewer.columnsPending.keySet().toLongArray();
        long[] order = new long[keys.length];
        for (int i = 0; i < keys.length; i++) order[i] = (long) UnitMath.rank(pcx, pcz, keys[i]) << 32 | i;
        Arrays.sort(order);
        for (int i = QUEUE; i < order.length; i++) viewer.columnsPending.remove(keys[(int) order[i]]);
    }

    // ---- chunk changes (FarTerrainService hands them over) ------------------------------------------------------------

    /**
     * A chunk ({@code Chunks.key}) loaded, was saved (changed) or unloaded: its unit and every coarser one over it are made
     * again, holders get them once they are quiet.
     */
    void changed(ServerLevel level, long chunk) {
        Dim dim = dim(level);
        int cx = Chunks.x(chunk), cz = Chunks.z(chunk);
        long now = System.currentTimeMillis();
        for (int scale = 0; scale <= FarColumn.MAX_SCALE; scale++) {
            long unit = FarColumn.unitOf(scale, cx, cz);
            dim.changedLast.put(unit, now);
            if (!dim.changedFirst.containsKey(unit)) dim.changedFirst.put(unit, now);
        }
    }

    // ---- the tick ---------------------------------------------------------------------------------------------------

    void tick(Map<java.util.UUID, Viewer> viewers, FarTerrainSettings settings) {
        ticks++;
        long deadline = System.nanoTime() + DONE_NANOS;
        for (int n = 0; n < 8192 && (n % 64 != 0 || System.nanoTime() < deadline); n++) {
            Runnable r = done.poll();
            if (r == null) break;
            r.run();
        }
        if (ticks % PUSH_TICKS == 0) pushes(viewers);
        dispatch(viewers);
        captures();
        for (Dim dim : dims.values()) pumpReads(dim);
        send(viewers, settings);
    }

    /**
     * Changed units that have settled: the cached one goes, and whoever holds it is sent the new one. A unit settles only
     * when nothing is making it (what is being made may be from before the change) and none of its children is still
     * waiting to settle (so it is made from the new ones); the finer scales are looked at first.
     */
    private void pushes(Map<java.util.UUID, Viewer> viewers) {
        long now = System.currentTimeMillis();
        for (Dim dim : dims.values()) {
            if (dim.changedLast.isEmpty()) continue;
            List<LongArrayList> ready = new ArrayList<>();
            for (int s = 0; s <= FarColumn.MAX_SCALE; s++) ready.add(new LongArrayList());
            for (Iterator<Long2LongOpenHashMap.Entry> it = dim.changedLast.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
                Long2LongOpenHashMap.Entry e = it.next();
                long key = e.getLongKey();
                int scale = FarColumn.scale(key);
                boolean settled = scale == 0 ? now - e.getLongValue() >= PUSH_DELAY_MS
                        : now - e.getLongValue() >= COARSE_PUSH_DELAY_MS || now - dim.changedFirst.get(key) >= COARSE_PUSH_MAX_MS;
                if (settled) ready.get(scale).add(key);
            }
            List<Viewer> holders = new ArrayList<>();
            for (Viewer viewer : viewers.values()) if (viewer.dimension == dim.level.dimension() && !viewer.columnsHeld.isEmpty()) holders.add(viewer);
            for (int scale = 0; scale <= FarColumn.MAX_SCALE; scale++) {
                for (long key : ready.get(scale)) {
                    if (dim.jobs.containsKey(key)) continue;
                    if (scale > 0) {
                        boolean waiting = false;
                        for (int i = 0; i < 4 && !waiting; i++) waiting = dim.changedLast.containsKey(FarColumn.child(key, i));
                        if (waiting) continue;
                        dim.dirty.add(key);
                    }
                    dim.changedLast.remove(key);
                    dim.changedFirst.remove(key);
                    uncache(dim, key);
                    for (Viewer viewer : holders) {
                        if (!viewer.columnsHeld.containsKey(key) || viewer.columnsPending.containsKey(key)) continue;
                        viewer.columnsPending.put(key, new Viewer.Pending(key, viewer.columnsHeld.get(key), true));
                        viewer.columnsFresh = true;
                    }
                }
            }
        }
    }

    /** Starts the nearest waiting units of each viewer: from the cache, joining a job running, or a new job. */
    private void dispatch(Map<java.util.UUID, Viewer> viewers) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Viewer viewer = viewers.get(player.getUUID());
            if (viewer == null || viewer.columnsPending.isEmpty() || viewer.dimension != player.level().dimension()) continue;
            Dim dim = dim((ServerLevel) player.level());
            int pcx = player.getBlockX() >> 4, pcz = player.getBlockZ() >> 4;
            long[] order = viewer.columnsOrder;
            boolean moved = Math.max(Math.abs(pcx - viewer.columnsSortX), Math.abs(pcz - viewer.columnsSortZ)) > 2;
            if (order == null || viewer.columnsCursor >= order.length
                    || (moved || viewer.columnsFresh) && ticks - viewer.columnsSortTick >= SORT_TICKS) {
                order = sort(viewer, pcx, pcz);
            }
            int i = viewer.columnsCursor, skipped = -1, started = 0;
            for (int looked = 0; i < order.length && looked < WALK && started < STARTS; i++, looked++) {
                long key = order[i];
                Viewer.Pending p = viewer.columnsPending.get(key);
                if (p == null) continue;
                Encoded cached = dim.cache.getAndMoveToLast(key);
                if (cached != null) {
                    viewer.columnsPending.remove(key);
                    deliver(dim, viewer, viewer.session, p, cached);
                    started++;
                    continue;
                }
                if (viewer.columnsInFlight >= IN_FLIGHT) break;
                boolean coarse = FarColumn.scale(key) >= 2;
                if (coarse && viewer.columnsInFlightCoarse >= IN_FLIGHT - FINE_RESERVED) {
                    if (skipped < 0) skipped = i;
                    continue;
                }
                viewer.columnsPending.remove(key);
                viewer.columnsInFlight++;
                if (coarse) viewer.columnsInFlightCoarse++;
                start(dim, key, UnitMath.rank(pcx, pcz, key)).waiters.add(new Waiter(viewer, viewer.session, p, coarse));
                started++;
            }
            viewer.columnsCursor = skipped >= 0 ? skipped : i;
        }
    }

    /** The viewer's waiting units, nearest first (looked at again every few ticks, not for every pick). */
    private long[] sort(Viewer viewer, int pcx, int pcz) {
        long[] keys = viewer.columnsPending.keySet().toLongArray();
        long[] order = new long[keys.length];
        for (int i = 0; i < keys.length; i++) order[i] = (long) UnitMath.rank(pcx, pcz, keys[i]) << 32 | i;
        Arrays.sort(order);
        for (int i = 0; i < order.length; i++) order[i] = keys[(int) order[i]];
        viewer.columnsOrder = order;
        viewer.columnsCursor = 0;
        viewer.columnsSortTick = ticks;
        viewer.columnsSortX = pcx;
        viewer.columnsSortZ = pcz;
        viewer.columnsFresh = false;
        return order;
    }

    /**
     * The job making a unit, joined if one runs. A chunk is captured (loaded) or read (saved); a coarser unit is taken
     * from disk when what it was built from is unchanged, else made from its children, each started the same way.
     */
    private Job start(Dim dim, long key, int rank) {
        Job job = dim.jobs.get(key);
        if (job != null) return job;
        job = new Job(key, rank);
        dim.jobs.put(key, job);
        int scale = FarColumn.scale(key);
        if (scale == 0) {
            int x = FarColumn.x(key), z = FarColumn.z(key);
            Queued q = new Queued(key, rank, UnitMath.morton(x, z));
            if (dim.level.getChunkSource().getChunkNow(x, z) != null) dim.captures.add(q);
            else dim.reads.add(q);
            return job;
        }
        boolean dirty = dim.dirty.remove(key);
        Job j = job;
        workers.get().run(Workers.IMPORT, rank, () -> {
            long[] stamps = null;
            Encoded stored = null;
            boolean saved = true;
            try {
                // none of its chunks saved (beyond what was generated): nothing to build or keep, unless one is loaded
                saved = UnitMath.anyChunk(key, dim.regions::existsNow);
                if (saved) {
                    stamps = UnitMath.childStamps(key, dim.regions::stamp);
                    UnitFiles.Stored s = dirty ? null : dim.files.read(key);
                    if (s != null && Arrays.equals(s.stamps(), stamps)) stored = s.encoded();
                }
            } catch (Throwable t) {
                BlockLand.LOGGER.debug("[BlockLand] Far chunk unit {} could not be looked up", key, t);
            }
            long[] st = stamps;
            Encoded hit = stored;
            boolean any = saved;
            done.add(() -> looked(dim, j, any, st, hit));
        });
        return job;
    }

    /** A coarse unit was looked up on disk: done, or its children are gathered (from the cache or their own jobs). */
    private void looked(Dim dim, Job job, boolean saved, @Nullable long[] stamps, @Nullable Encoded stored) {
        if (stored != null) {
            dim.fromDisk[FarColumn.scale(job.key)]++;
            finish(dim, job.key, stored, true);
            return;
        }
        if (!saved && !UnitMath.anyChunk(job.key, (x, z) -> dim.level.getChunkSource().getChunkNow(x, z) != null)) {
            finish(dim, job.key, empty(job.key), true);
            return;
        }
        job.stamps = stamps;
        job.children = new Encoded[4];
        job.missing = 4;
        for (int i = 0; i < 4; i++) {
            long child = FarColumn.child(job.key, i);
            Encoded cached = dim.cache.getAndMoveToLast(child);
            if (cached != null) {
                childReady(dim, job, i, cached, true);
                continue;
            }
            Job c = start(dim, child, job.rank);
            c.parents.add(job);
            c.slots.add(i);
        }
    }

    private void childReady(Dim dim, Job job, int slot, Encoded encoded, boolean ok) {
        if (job.children == null || job.children[slot] != null) return;
        job.children[slot] = encoded;
        if (!ok) job.childFailed = true;
        if (--job.missing > 0) return;
        Encoded[] kids = job.children;
        long[] stamps = job.stamps;
        boolean whole = !job.childFailed;
        long key = job.key;
        ServerLevel level = dim.level;
        workers.get().run(Workers.TILE, job.rank, () -> {
            Encoded built;
            boolean made = true;
            try {
                byte[][] bytes = new byte[4][];
                for (int i = 0; i < 4; i++) bytes[i] = kids[i].bytes();
                built = encode(UnitBuilder.build(dim.biomes, key, level.getMinY(), level.getHeight(), bytes));
            } catch (Throwable t) {
                BlockLand.LOGGER.debug("[BlockLand] Far chunk unit {} could not be built", key, t);
                built = empty(key);
                made = false;
            }
            Encoded result = built;
            boolean cache = made && whole;
            if (cache && stamps != null) {
                workers.get().run(Workers.WRITE, 0, () -> {
                    try {
                        dim.files.write(key, stamps, result);
                    } catch (IOException e) {
                        BlockLand.LOGGER.debug("[BlockLand] Far chunk unit {} could not be written", key, e);
                    }
                });
            }
            done.add(() -> {
                if (cache) dim.built[FarColumn.scale(key)]++;
                finish(dim, key, result, cache);
            });
        });
    }

    /** Loaded chunks are read here (their sections and light, on the server thread), encoded on a worker. */
    private void captures() {
        long deadline = System.nanoTime() + CAPTURE_NANOS;
        for (Dim dim : dims.values()) {
            while (!dim.captures.isEmpty() && System.nanoTime() < deadline) {
                Queued q = dim.captures.poll();
                long key = q.key();
                LevelChunk chunk = dim.level.getChunkSource().getChunkNow(FarColumn.x(key), FarColumn.z(key));
                if (chunk == null) {
                    // gone meanwhile: its saved data then
                    dim.reads.add(q);
                    continue;
                }
                FarColumn column;
                try {
                    column = capture(dim.level, chunk);
                } catch (Throwable t) {
                    BlockLand.LOGGER.debug("[BlockLand] Far chunk capture failed at {}", chunk.getPos(), t);
                    finish(dim, key, empty(key), false);
                    continue;
                }
                workers.get().run(Workers.TILE, q.rank(), () -> {
                    Encoded encoded = encode(column);
                    done.add(() -> {
                        dim.built[0]++;
                        finish(dim, key, encoded, true);
                    });
                });
            }
        }
    }

    /**
     * Saved chunks: one the region file does not hold is not there (the chunk storage is not asked: it also serves the
     * game's own loads); the others the chunk storage reads, a worker parses only what a unit needs.
     */
    private void pumpReads(Dim dim) {
        while (dim.readsRunning < READS_IN_FLIGHT && !dim.reads.isEmpty()) {
            Queued q = dim.reads.poll();
            long key = q.key();
            int x = FarColumn.x(key), z = FarColumn.z(key);
            if (dim.level.getChunkSource().getChunkNow(x, z) != null) {
                // loaded meanwhile: the live chunk is newer than its save
                dim.captures.add(q);
                continue;
            }
            dim.readsRunning++;
            workers.get().run(Workers.IMPORT, q.rank(), () -> {
                boolean there;
                try {
                    there = dim.regions.existsNow(x, z);
                } catch (Throwable t) {
                    there = true;
                }
                if (!there) {
                    done.add(() -> {
                        dim.readsRunning--;
                        dim.built[0]++;
                        finish(dim, key, empty(key), true);
                    });
                    return;
                }
                CompletableFuture<Optional<CompoundTag>> read;
                try {
                    read = dim.level.getChunkSource().chunkMap.read(new ChunkPos(x, z));
                } catch (Throwable t) {
                    read = CompletableFuture.failedFuture(t);
                }
                read.whenComplete((tag, error) -> workers.get().run(Workers.IMPORT, q.rank(), () -> parsed(dim, key, tag, error)));
            });
        }
    }

    /** A saved chunk read (worker): parsed and encoded, handed to the server thread. */
    private void parsed(Dim dim, long key, @Nullable Optional<CompoundTag> tag, @Nullable Throwable error) {
        Encoded encoded;
        boolean ok = error == null;
        try {
            FarColumn column = ok && tag != null && tag.isPresent() ? parse(dim.level, tag.get()) : null;
            encoded = column == null ? empty(key) : encode(column);
        } catch (Throwable t) {
            BlockLand.LOGGER.debug("[BlockLand] Far chunk read failed at {}, {}", FarColumn.x(key), FarColumn.z(key), t);
            encoded = empty(key);
            ok = false;
        }
        Encoded result = encoded;
        boolean cache = ok;
        done.add(() -> {
            dim.readsRunning--;
            if (cache) dim.built[0]++;
            finish(dim, key, result, cache);
        });
    }

    /**
     * A unit is ready: cached (unless it failed: then it is tried again when next asked for), every player waiting for it
     * gets their answer, every coarser unit waiting for it has one child more.
     */
    private void finish(Dim dim, long key, Encoded encoded, boolean cache) {
        if (cache) put(dim, key, encoded);
        Job job = dim.jobs.remove(key);
        if (job == null) return;
        for (Waiter w : job.waiters) {
            // a stay that ended took its counts with it
            if (w.session != w.viewer.session) continue;
            w.viewer.columnsInFlight = Math.max(0, w.viewer.columnsInFlight - 1);
            if (w.coarse) w.viewer.columnsInFlightCoarse = Math.max(0, w.viewer.columnsInFlightCoarse - 1);
            deliver(dim, w.viewer, w.session, w.pending, encoded);
        }
        for (int i = 0; i < job.parents.size(); i++) childReady(dim, job.parents.get(i), (int) job.slots.getLong(i), encoded, cache);
    }

    /** The answer for one player: cut to their view limit on a worker when the unit reaches beyond it. */
    private void deliver(Dim dim, Viewer viewer, int session, Viewer.Pending pending, Encoded encoded) {
        long key = pending.key();
        if (!pending.push()) {
            chunksAnswered += 1L << 2 * FarColumn.scale(key);
            chunksAbsent += encoded.absent();
        }
        BoundingBox limit = viewer.limit;
        if (encoded.bytes() == null || UnitMath.within(limit, key)) {
            viewer.columnsOutbox.add(new Viewer.Outgoing(session, answer(key, encoded, pending.version()), pending.push()));
            return;
        }
        workers.get().run(Workers.TILE, -1, () -> {
            FarTerrainPackets.Answer answer;
            try {
                FarColumn cut = UnitBuilder.clip(dim.biomes, encoded.bytes(), limit);
                answer = cut == null ? FarTerrainPackets.Answer.of(key, FarTerrainPackets.EMPTY, EMPTY_VERSION)
                        : cut.version == pending.version() ? FarTerrainPackets.Answer.of(key, FarTerrainPackets.UNCHANGED, cut.version)
                        : FarTerrainPackets.Answer.data(key, cut.version, cut.encode());
            } catch (Throwable t) {
                BlockLand.LOGGER.debug("[BlockLand] Far chunk unit {} could not be cut to a view limit", key, t);
                answer = FarTerrainPackets.Answer.of(key, FarTerrainPackets.DENIED, 0);
            }
            viewer.columnsOutbox.add(new Viewer.Outgoing(session, answer, pending.push()));
        });
    }

    static FarTerrainPackets.Answer answer(long key, Encoded encoded, long held) {
        if (encoded.bytes() == null) return FarTerrainPackets.Answer.of(key, FarTerrainPackets.EMPTY, EMPTY_VERSION);
        if (encoded.version() == held) return FarTerrainPackets.Answer.of(key, FarTerrainPackets.UNCHANGED, held);
        return FarTerrainPackets.Answer.data(key, encoded.version(), encoded.bytes());
    }

    /** Sends each viewer's answers within their byte budget, packed into packets of at most {@link #PACKET_BYTES}. */
    private void send(Map<java.util.UUID, Viewer> viewers, FarTerrainSettings settings) {
        double perTick = settings.columnBytesPerSecond() / 20.0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Viewer viewer = viewers.get(player.getUUID());
            if (viewer == null) continue;
            viewer.columnBudget = Math.min(viewer.columnBudget + perTick, Math.max(perTick, settings.columnBytesPerSecond()));
            if (viewer.columnsOutbox.isEmpty()) continue;
            List<FarTerrainPackets.Answer> packet = new ArrayList<>();
            int bytes = 0;
            while (viewer.columnBudget > 0) {
                Viewer.Outgoing out = viewer.columnsOutbox.poll();
                if (out == null) break;
                if (out.session() != viewer.session) continue;
                FarTerrainPackets.Answer a = out.answer();
                // a push nobody needs: the client already holds that version
                if (out.push() && (a.kind() == FarTerrainPackets.UNCHANGED || a.kind() == FarTerrainPackets.DENIED
                        || viewer.columnsHeld.containsKey(a.key()) && viewer.columnsHeld.get(a.key()) == a.version())) {
                    continue;
                }
                if (a.kind() != FarTerrainPackets.DENIED) viewer.columnsHeld.put(a.key(), a.version());
                int size = FarChunkPackets.S2CColumns.size(a);
                if (!packet.isEmpty() && (bytes + size > PACKET_BYTES || packet.size() >= FarChunkPackets.MAX_ANSWERS)) {
                    BLNetwork.sendTo(player, new FarChunkPackets.S2CColumns(viewer.session, packet));
                    packet = new ArrayList<>();
                    bytes = 0;
                }
                packet.add(a);
                bytes += size;
                viewer.columnBudget -= size;
            }
            if (!packet.isEmpty()) BLNetwork.sendTo(player, new FarChunkPackets.S2CColumns(viewer.session, packet));
        }
    }

    // ---- the cache --------------------------------------------------------------------------------------------------

    private static void put(Dim dim, long key, Encoded encoded) {
        Encoded old = dim.cache.put(key, encoded);
        if (old != null) dim.cacheBytes -= old.size();
        dim.cacheBytes += encoded.size();
        while (dim.cacheBytes > CACHE_BYTES && !dim.cache.isEmpty()) {
            Encoded evicted = dim.cache.removeFirst();
            dim.cacheBytes -= evicted.size();
        }
    }

    private static void uncache(Dim dim, long key) {
        Encoded old = dim.cache.remove(key);
        if (old != null) dim.cacheBytes -= old.size();
    }

    // ---- reading chunks ---------------------------------------------------------------------------------------------

    /** A loaded full chunk's sections and light (server thread). */
    static FarColumn capture(ServerLevel level, LevelChunk chunk) {
        int min = level.getMinSectionY(), count = level.getSectionsCount();
        FarColumn column = new FarColumn(chunk.getPos().x, chunk.getPos().z, min, count);
        LevelChunkSection[] sections = chunk.getSections();
        var sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var block = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int i = 0; i < Math.min(count, sections.length); i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) column.sections[i] = sectionBytes(section);
            SectionPos pos = SectionPos.of(chunk.getPos().x, min + i, chunk.getPos().z);
            column.skyLight[i] = light(sky.getDataLayerData(pos));
            column.blockLight[i] = light(block.getDataLayerData(pos));
        }
        return column;
    }

    /**
     * A saved chunk's unit of scale 0 (worker): only full chunks, else null (the generation or a later save makes it full).
     * A chunk an older game version wrote is brought up to date first, as loading it would.
     */
    @Nullable
    static FarColumn parse(ServerLevel level, CompoundTag tag) {
        tag = SavedChunks.current(level, tag);
        SerializableChunkData data = SerializableChunkData.parse(level, level.registryAccess(), tag);
        if (data == null || data.chunkStatus() != ChunkStatus.FULL) return null;
        int min = level.getMinSectionY(), count = level.getSectionsCount();
        FarColumn column = new FarColumn(data.chunkPos().x, data.chunkPos().z, min, count);
        for (SerializableChunkData.SectionData section : data.sectionData()) {
            int i = section.y() - min;
            if (i < 0 || i >= count) continue;
            if (section.chunkSection() != null && !section.chunkSection().hasOnlyAir()) column.sections[i] = sectionBytes(section.chunkSection());
            column.skyLight[i] = light(section.skyLight());
            column.blockLight[i] = light(section.blockLight());
        }
        return column;
    }

    /** The section as Minecraft's chunk packet writes it (block count, states, biomes). */
    static byte[] sectionBytes(LevelChunkSection section) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(section.getSerializedSize()));
        try {
            section.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** A copy of a light layer's nibbles, or null when it holds none (the client derives the column's light). */
    @Nullable
    static byte[] light(@Nullable DataLayer layer) {
        if (layer == null || layer.isEmpty()) return null;
        return layer.copy().getData();
    }

    /** A unit encoded: EMPTY only when none of its chunks exists (a chunk that is there but all air is real air). */
    static Encoded encode(FarColumn column) {
        int side = 1 << 2 * column.scale;
        int present = column.presentCount();
        if (present == 0) return new Encoded(EMPTY_VERSION, null, side);
        byte[] bytes = column.encode();
        return new Encoded(column.version, bytes, side - present);
    }

    /** Nothing of the unit there. */
    static Encoded empty(long key) {
        return new Encoded(EMPTY_VERSION, null, 1 << 2 * FarColumn.scale(key));
    }

    /** Status lines for {@code /bl far status}: per scale the units cached, built (from disk) and being made. */
    List<String> status() {
        int scales = FarColumn.MAX_SCALE + 1;
        int[] cached = new int[scales], making = new int[scales];
        long[] built = new long[scales], fromDisk = new long[scales];
        long bytes = 0;
        int reads = 0;
        for (Dim dim : dims.values()) {
            for (long key : dim.cache.keySet()) cached[FarColumn.scale(key)]++;
            for (long key : dim.jobs.keySet()) making[FarColumn.scale(key)]++;
            for (int s = 0; s < scales; s++) {
                built[s] += dim.built[s];
                fromDisk[s] += dim.fromDisk[s];
            }
            bytes += dim.cacheBytes;
            reads += dim.reads.size() + dim.readsRunning + dim.captures.size();
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("real far chunks: %.1f MB cached, %d chunk reads waiting; of %d chunks answered %d not generated yet",
                bytes / 1048576.0, reads, chunksAnswered, chunksAbsent));
        for (int s = 0; s < scales; s++) {
            lines.add(String.format("  scale %d (%d×%d chunks): %d cached, %d built, %d from disk, %d being made", s, 1 << s, 1 << s,
                    cached[s], built[s], fromDisk[s], making[s]));
        }
        return lines;
    }
}
