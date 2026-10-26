package com.blockland.server.farterrain;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarTile;
import com.blockland.network.BLNetwork;
import com.blockland.network.FarTerrainPacketListeners;
import com.blockland.network.packet.FarTerrainPackets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The server side of far terrain (远景地平线, rendering spec §22): it tells each player what is offered in the level they
 * entered, takes their tile requests, has workers bring the tiles up to date (live captures, saved chunks, the
 * generator's estimate) and sends the answers within each player's byte budget; tiles a player holds that change later
 * are pushed to them. It knows the rest of the server only through {@code BlockLandServer}'s wiring: the chunk events it
 * is handed, the packet listener, the settings supplier and the view limit function.
 */
public final class FarTerrainService implements FarTerrainPacketListeners.Server {
    /** Requests kept per player (the farthest are dropped beyond). */
    private static final int QUEUE = 512;
    /** Tiles of one player being worked on at once. */
    private static final int IN_FLIGHT = 16;
    /** Largest {@code S2CTiles} (bytes of answers). */
    private static final int PACKET_BYTES = 128 * 1024;
    /** Server-thread time per tick for capturing chunks. */
    private static final long CAPTURE_NANOS = 1_000_000;
    /** Loaded chunks looked at per tick for unsaved changes, and how old a capture of a changed chunk may get. */
    private static final int SWEEP = 48;
    private static final long RECAPTURE_MS = 30_000;
    /** Ticks between view limit checks, tile writes and index saves. */
    private static final int LIMIT_TICKS = 20, FLUSH_TICKS = 100, INDEX_TICKS = 6000;

    private final MinecraftServer server;
    private final Supplier<FarTerrainSettings> settingsSource;
    private volatile FarTerrainSettings settings;
    private Function<ServerPlayer, BoundingBox> viewLimit = player -> null;
    private final SurfaceTable surfaces;
    @Nullable
    private Workers workers;
    @Nullable
    private Generation generation;
    /** Real far chunks (rendering spec §23). */
    private final ColumnService columns;
    private final Map<ResourceKey<Level>, DimensionTerrain> dimensions = new HashMap<>();
    private final Map<ResourceKey<Level>, Live> live = new HashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private int sessions;
    private int ticks;
    private boolean stopped;

    /** Loaded chunks of a dimension and what waits to be captured (server thread). */
    private static final class Live {
        final Set<Long> pending = ConcurrentHashMap.newKeySet();
        final LongOpenHashSet loaded = new LongOpenHashSet();
        final ArrayDeque<Long> sweep = new ArrayDeque<>();
        final Long2LongOpenHashMap captured = new Long2LongOpenHashMap();
    }

    public FarTerrainService(MinecraftServer server, Supplier<FarTerrainSettings> settings) {
        this.server = server;
        this.settingsSource = settings;
        this.settings = settings.get();
        this.surfaces = SurfaceTable.parse(readSurfaces(server));
        this.columns = new ColumnService(server, this::workers);
    }

    /** Players in an arena match only see their arena ({@code null} = no limit). */
    public void setViewLimit(@Nullable Function<ServerPlayer, BoundingBox> limit) {
        this.viewLimit = limit == null ? player -> null : limit;
    }

    @Nullable
    private static JsonObject readSurfaces(MinecraftServer server) {
        Optional<Resource> resource = server.getResourceManager().getResource(BlockLand.id("farterrain/surfaces.json"));
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] data/blockland/farterrain/surfaces.json missing: far terrain estimates use grass everywhere");
            return null;
        }
        try (Reader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] data/blockland/farterrain/surfaces.json unreadable: far terrain estimates use grass everywhere", e);
            return null;
        }
    }

    private Workers workers() {
        if (workers == null) workers = new Workers(settings.threads());
        return workers;
    }

    private DimensionTerrain dimension(ServerLevel level) {
        DimensionTerrain dim = dimensions.get(level.dimension());
        if (dim == null) {
            dim = new DimensionTerrain(level, surfaces, () -> settings, workers());
            dimensions.put(level.dimension(), dim);
        }
        return dim;
    }

    private Live live(ServerLevel level) {
        return live.computeIfAbsent(level.dimension(), k -> new Live());
    }

    // ---- chunk events (BlockLandServer wires them) ---------------------------------------------------------------------

    /** A full chunk loaded: captured in the next ticks (the load may come before it is promoted to full). */
    public void chunkLoaded(ServerLevel level, ChunkPos pos) {
        if (stopped || !settings.enabled()) return;
        if (!server.isSameThread()) {
            server.execute(() -> chunkLoaded(level, pos));
            return;
        }
        Live l = live(level);
        long key = Chunks.key(pos.x, pos.z);
        if (l.loaded.add(key)) l.sweep.add(key);
        l.pending.add(key);
        columns.changed(level, Chunks.key(pos.x, pos.z));
    }

    /** A full chunk was saved (it changed): captured again in the next ticks. */
    public void chunkSaved(ServerLevel level, ChunkPos pos) {
        if (stopped || !settings.enabled()) return;
        if (!server.isSameThread()) {
            server.execute(() -> chunkSaved(level, pos));
            return;
        }
        live(level).pending.add(Chunks.key(pos.x, pos.z));
        columns.changed(level, Chunks.key(pos.x, pos.z));
    }

    /** A chunk unloads: a change not captured yet is captured now, while its data is still at hand. */
    public void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        if (stopped || !settings.enabled()) return;
        long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
        if (!server.isSameThread()) {
            // not ours to read off the server thread: the saved data is read when needed
            server.execute(() -> {
                Live l = live(level);
                l.loaded.remove(key);
                l.pending.remove(key);
                dimension(level).real.forget(key);
                columns.changed(level, key);
            });
            return;
        }
        Live l = live(level);
        l.loaded.remove(key);
        if (l.pending.remove(key) || chunk.isUnsaved()) {
            capture(level, chunk, l);
            columns.changed(level, key);
        }
    }

    /**
     * Blocks in this box changed wholesale (a map pasted into an arena, an arena cleared): loaded chunks are captured
     * again, others are read from disk when next needed.
     */
    public void areaChanged(ServerLevel level, BoundingBox box) {
        if (stopped) return;
        Live l = live(level);
        DimensionTerrain dim = dimension(level);
        for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                long key = Chunks.key(cx, cz);
                if (l.loaded.contains(key)) l.pending.add(key);
                else dim.real.forget(key);
                columns.changed(level, key);
            }
        }
    }

    private void capture(ServerLevel level, LevelChunk chunk, Live l) {
        ChunkColumns columns = ChunkColumns.capture(chunk);
        l.captured.put(columns.chunk(), System.currentTimeMillis());
        DimensionTerrain dim = dimension(level);
        workers().run(Workers.MERGE, 0, () -> dim.merge(columns));
    }

    /** Captures what waits, within the tick's budget; then looks at a few loaded chunks for unsaved changes. */
    private void captures() {
        long deadline = System.nanoTime() + CAPTURE_NANOS;
        long now = System.currentTimeMillis();
        for (ServerLevel level : server.getAllLevels()) {
            Live l = live.get(level.dimension());
            if (l == null) continue;
            Iterator<Long> it = l.pending.iterator();
            while (it.hasNext() && System.nanoTime() < deadline) {
                long key = it.next();
                it.remove();
                LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
                if (chunk != null) {
                    capture(level, chunk, l);
                } else if (now - l.captured.get(key) > 5_000) {
                    // gone before it could be captured: its saved data is read when a tile needs it
                    dimension(level).real.forget(key);
                }
            }
            for (int n = 0; n < SWEEP && !l.sweep.isEmpty(); n++) {
                long key = l.sweep.poll();
                if (!l.loaded.contains(key)) continue;
                l.sweep.add(key);
                if (now - l.captured.get(key) < RECAPTURE_MS) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
                if (chunk != null && chunk.isUnsaved()) l.pending.add(key);
            }
        }
    }

    // ---- players --------------------------------------------------------------------------------------------------------

    /** The player left the world. */
    public void forget(ServerPlayer player) {
        viewers.remove(player.getUUID());
    }

    @Override
    public void onRequest(ServerPlayer player, FarTerrainPackets.C2SRequest request) {
        Viewer viewer = viewers.get(player.getUUID());
        if (viewer == null || stopped || request.session() != viewer.session || viewer.dimension != player.level().dimension()) return;
        if (request.keys().length == 0) {
            viewer.pending.clear();
            return;
        }
        DimensionTerrain dim = dimension((ServerLevel) player.level());
        boolean offered = settings.enabled() && dim.offered;
        for (int i = 0; i < request.keys().length; i++) {
            long key = request.keys()[i];
            if (!offered || !allowed(player, viewer, key)) {
                viewer.outbox.add(new Viewer.Outgoing(viewer.session, FarTerrainPackets.Answer.of(key, FarTerrainPackets.DENIED, 0), false));
                continue;
            }
            viewer.pending.put(key, new Viewer.Pending(key, request.versions()[i], false));
        }
        while (viewer.pending.size() > QUEUE) {
            // too much asked at once: the farthest wait for the client to ask again
            long farthest = 0;
            double worst = -1;
            for (Viewer.Pending p : viewer.pending.values()) {
                double d = distance(player, p.key());
                if (d > worst) {
                    worst = d;
                    farthest = p.key();
                }
            }
            viewer.pending.remove(farthest);
        }
    }

    /** Real far chunks (rendering spec §23): columns beyond the view distance. */
    @Override
    public void onColumns(ServerPlayer player, com.blockland.network.packet.FarChunkPackets.C2SRequest request) {
        Viewer viewer = viewers.get(player.getUUID());
        if (viewer == null || stopped || request.session() != viewer.session || viewer.dimension != player.level().dimension()) return;
        boolean offered = settings.enabled() && dimension((ServerLevel) player.level()).offered;
        columns.onRequest(player, viewer, request, offered, settings);
    }

    /** A valid level, within reach of the player (one tile more), touching their view limit. */
    private boolean allowed(ServerPlayer player, Viewer viewer, long key) {
        int level = FarTile.level(key);
        if (level < 0 || level > FarTile.MAX_LEVEL) return false;
        if (distance(player, key) > settings.maxDistance() + FarTile.span(level)) return false;
        BoundingBox limit = viewer.limit;
        if (limit == null) return true;
        int span = FarTile.span(level);
        int x0 = FarTile.tx(key) * span, z0 = FarTile.tz(key) * span;
        return x0 <= limit.maxX() && x0 + span > limit.minX() && z0 <= limit.maxZ() && z0 + span > limit.minZ();
    }

    /** Horizontal distance from the player to the nearest point of a tile (0 inside it). */
    private static double distance(ServerPlayer player, long key) {
        int span = FarTile.span(FarTile.level(key));
        double x0 = (double) FarTile.tx(key) * span, z0 = (double) FarTile.tz(key) * span;
        double dx = Math.max(0, Math.max(x0 - player.getX(), player.getX() - (x0 + span)));
        double dz = Math.max(0, Math.max(z0 - player.getZ(), player.getZ() - (z0 + span)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** New stays (another level, another view limit) get a hello; viewers of players gone are dropped. */
    private void viewers() {
        Set<UUID> present = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            present.add(id);
            Viewer viewer = viewers.computeIfAbsent(id, Viewer::new);
            ResourceKey<Level> dimension = player.level().dimension();
            boolean changed = viewer.dimension != dimension;
            BoundingBox limit = viewer.limit;
            if (changed || --viewer.limitCheck <= 0) {
                viewer.limitCheck = LIMIT_TICKS;
                limit = viewLimit.apply(player);
                if (!Objects.equals(limit, viewer.limit)) changed = true;
            }
            if (changed) hello(player, viewer, limit);
        }
        viewers.keySet().retainAll(present);
    }

    private void hello(ServerPlayer player, Viewer viewer, @Nullable BoundingBox limit) {
        ServerLevel level = (ServerLevel) player.level();
        viewer.reset(++sessions, level.dimension(), limit);
        DimensionTerrain dim = dimension(level);
        long worldId = worldId(level, limit);
        BLNetwork.sendTo(player, new FarTerrainPackets.S2CHello(viewer.session, settings.enabled() && dim.offered, settings.maxDistance(),
                worldId, dim.id, level.getMinY(), level.getMaxY()));
    }

    /** The client's cache key: the world's seed, the dimension and the player's view limit. */
    private long worldId(ServerLevel level, @Nullable BoundingBox limit) {
        long h = server.getWorldData().worldGenOptions().seed() * 0x9E3779B97F4A7C15L;
        h = (h ^ level.dimension().location().toString().hashCode()) * 0xBF58476D1CE4E5B9L;
        if (limit != null) {
            h = (h ^ limit.minX()) * 0x94D049BB133111EBL;
            h = (h ^ limit.minZ()) * 0x94D049BB133111EBL;
            h = (h ^ limit.maxX()) * 0x94D049BB133111EBL;
            h = (h ^ limit.maxZ()) * 0x94D049BB133111EBL;
        }
        return h ^ h >>> 31;
    }

    /** Tiles that changed and that a viewer holds are refreshed for them (the answer is dropped if nothing they see changed). */
    private void pushes() {
        for (Map.Entry<ResourceKey<Level>, DimensionTerrain> e : dimensions.entrySet()) {
            DimensionTerrain dim = e.getValue();
            if (dim.changed.isEmpty()) continue;
            LongOpenHashSet keys = new LongOpenHashSet();
            Long key;
            int n = 0;
            while (n++ < 8192 && (key = dim.changed.poll()) != null) keys.add((long) key);
            for (Viewer viewer : viewers.values()) {
                if (viewer.dimension != e.getKey() || viewer.held.isEmpty()) continue;
                for (long k : keys) {
                    if (viewer.held.containsKey(k) && !viewer.pending.containsKey(k)) {
                        viewer.pending.put(k, new Viewer.Pending(k, viewer.held.get(k), true));
                    }
                }
            }
        }
    }

    /** Starts the nearest waiting tiles of each viewer on the workers. */
    private void dispatch() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Viewer viewer = viewers.get(player.getUUID());
            if (viewer == null || viewer.pending.isEmpty() || viewer.dimension != player.level().dimension()) continue;
            DimensionTerrain dim = dimension((ServerLevel) player.level());
            while (viewer.inFlight.get() < IN_FLIGHT && !viewer.pending.isEmpty()) {
                Viewer.Pending next = null;
                double best = Double.MAX_VALUE;
                for (Viewer.Pending p : viewer.pending.values()) {
                    // nearest first; among tiles at the same distance (the player inside them) the coarse ones
                    double d = distance(player, p.key()) - FarTile.level(p.key()) * 0.01;
                    if (d < best) {
                        best = d;
                        next = p;
                    }
                }
                if (next == null) break;
                viewer.pending.remove(next.key());
                start(viewer, dim, next, best);
            }
        }
    }

    private void start(Viewer viewer, DimensionTerrain dim, Viewer.Pending p, double order) {
        int session = viewer.session;
        BoundingBox limit = viewer.limit;
        viewer.inFlight.incrementAndGet();
        dim.serve(p.key(), order).whenComplete((entry, error) -> {
            try {
                FarTerrainPackets.Answer answer;
                if (error != null || entry == null) {
                    if (error != null) BlockLand.LOGGER.debug("[BlockLand] Far terrain tile failed", error);
                    answer = FarTerrainPackets.Answer.of(p.key(), FarTerrainPackets.DENIED, 0);
                } else {
                    answer = answer(entry, p.version(), limit);
                }
                viewer.outbox.add(new Viewer.Outgoing(session, answer, p.push()));
            } catch (Throwable t) {
                BlockLand.LOGGER.debug("[BlockLand] Far terrain answer failed", t);
                viewer.outbox.add(new Viewer.Outgoing(session, FarTerrainPackets.Answer.of(p.key(), FarTerrainPackets.DENIED, 0), p.push()));
            } finally {
                viewer.inFlight.decrementAndGet();
            }
        });
    }

    /** The answer for a client holding {@code version}: unchanged, empty, or the tile (cut to the view limit). */
    static FarTerrainPackets.Answer answer(TileEntry entry, long version, @Nullable BoundingBox limit) {
        long key = entry.key;
        if (limit == null) {
            DimensionTerrain.Encoded encoded = DimensionTerrain.encoded(entry);
            if (encoded.version() == version) return FarTerrainPackets.Answer.of(key, FarTerrainPackets.UNCHANGED, version);
            if (encoded.empty()) return FarTerrainPackets.Answer.of(key, FarTerrainPackets.EMPTY, encoded.version());
            return FarTerrainPackets.Answer.data(key, encoded.version(), encoded.bytes());
        }
        FarTile copy;
        synchronized (entry) {
            copy = entry.copy();
        }
        clip(copy, limit);
        long clipped = copy.computeVersion();
        if (clipped == version) return FarTerrainPackets.Answer.of(key, FarTerrainPackets.UNCHANGED, version);
        if (copy.isEmpty()) return FarTerrainPackets.Answer.of(key, FarTerrainPackets.EMPTY, clipped);
        return FarTerrainPackets.Answer.data(key, clipped, copy.encode());
    }

    /** Cells sampled outside the limit become empty. */
    static void clip(FarTile tile, BoundingBox limit) {
        for (int j = 0; j < FarTile.SIZE; j++) {
            int z = tile.sampleZ(j);
            boolean rowInside = z >= limit.minZ() && z <= limit.maxZ();
            for (int i = 0; i < FarTile.SIZE; i++) {
                int x = tile.sampleX(i);
                if (!rowInside || x < limit.minX() || x > limit.maxX()) tile.clear(FarTile.index(i, j));
            }
        }
    }

    /** Sends each viewer's answers within their byte budget, packed into packets of at most {@link #PACKET_BYTES}. */
    private void send() {
        double perTick = settings.bytesPerSecond() / 20.0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Viewer viewer = viewers.get(player.getUUID());
            if (viewer == null) continue;
            viewer.budget = Math.min(viewer.budget + perTick, Math.max(perTick, settings.bytesPerSecond()));
            if (viewer.outbox.isEmpty()) continue;
            List<FarTerrainPackets.Answer> packet = new ArrayList<>();
            int bytes = 0;
            while (viewer.budget > 0) {
                Viewer.Outgoing out = viewer.outbox.poll();
                if (out == null) break;
                if (out.session() != viewer.session) continue;
                FarTerrainPackets.Answer a = out.answer();
                if (out.push() && (a.kind() == FarTerrainPackets.UNCHANGED || a.kind() == FarTerrainPackets.DENIED
                        || viewer.held.containsKey(a.key()) && viewer.held.get(a.key()) == a.version())) {
                    continue;
                }
                if (a.kind() != FarTerrainPackets.DENIED) viewer.held.put(a.key(), a.version());
                int size = FarTerrainPackets.S2CTiles.size(a);
                if (!packet.isEmpty() && (bytes + size > PACKET_BYTES || packet.size() >= FarTerrainPackets.MAX_ANSWERS)) {
                    BLNetwork.sendTo(player, new FarTerrainPackets.S2CTiles(viewer.session, packet));
                    packet = new ArrayList<>();
                    bytes = 0;
                }
                packet.add(a);
                bytes += size;
                viewer.budget -= size;
            }
            if (!packet.isEmpty()) BLNetwork.sendTo(player, new FarTerrainPackets.S2CTiles(viewer.session, packet));
        }
    }

    // ---- the tick -------------------------------------------------------------------------------------------------------

    public void tick() {
        if (stopped) return;
        settings = settingsSource.get();
        ticks++;
        viewers();
        if (settings.enabled()) {
            captures();
            pushes();
            dispatch();
            if (generation == null) generation = new Generation(server, workers());
            generation.tick(settings, this::dimension, player -> {
                // the real far chunks a player asks for are generated as far as they reach (spec §23)
                Viewer viewer = viewers.get(player.getUUID());
                return viewer == null ? 0 : viewer.columnReach << 4;
            });
            columns.tick(viewers, settings);
        }
        send();
        for (DimensionTerrain dim : dimensions.values()) {
            // changed tiles go to disk; sooner while the cache is over its size (only written tiles can leave it)
            if (ticks % FLUSH_TICKS == 0 || ticks % LIMIT_TICKS == 0 && dim.crowded()) dim.flush(dim.crowded() ? 128 : 48, false);
        }
        if (ticks % INDEX_TICKS == 0) {
            for (DimensionTerrain dim : dimensions.values()) workers().run(Workers.WRITE, 1, dim::saveIndex);
            for (Live l : live.values()) l.captured.keySet().removeIf((long key) -> !l.loaded.contains(key));
        }
    }

    /** Server stopping: workers end, changed tiles and the indexes are written, chunks still waiting are read again next time. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        if (generation != null) generation.stop();
        if (workers != null) workers.shutdown();
        long deadline = System.currentTimeMillis() + 15_000;
        for (Map.Entry<ResourceKey<Level>, DimensionTerrain> e : dimensions.entrySet()) {
            DimensionTerrain dim = e.getValue();
            Live l = live.get(e.getKey());
            if (l != null) for (long key : l.pending) dim.real.forget(key);
            while (System.currentTimeMillis() < deadline && dim.flush(64, true) > 0) {
                // until every changed tile is on disk (or time is up)
            }
            dim.saveIndex();
        }
        viewers.clear();
        dimensions.clear();
        live.clear();
    }

    /** The estimate against the generator's exact columns in this level, measured off the server thread. */
    public java.util.concurrent.CompletableFuture<List<String>> probe(ServerLevel level, int columns) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Estimator estimator = Estimator.noiseOf(level);
            return estimator == null ? List.of(level.dimension().location() + ": no noise generator") : estimator.probe(columns);
        });
    }

    /** Status for a command: dimensions, tiles in memory, captured chunks, players served, queued work. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("far terrain: %s, %d players, %d tasks queued", settings.enabled() ? "on" : "off", viewers.size(),
                workers == null ? 0 : workers.pending()));
        if (generation != null) lines.add("  " + generation.status());
        for (String line : columns.status()) lines.add("  " + line);
        for (DimensionTerrain dim : dimensions.values()) {
            lines.add(String.format("  %s: %s, %d tiles in memory, %d chunks known", dim.id, dim.offered ? "offered" : "not offered",
                    dim.cached(), dim.real.size()));
        }
        return lines;
    }
}
