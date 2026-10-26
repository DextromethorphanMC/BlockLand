package com.blockland.client.graphics.far;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarTile;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.FarTerrainPackets;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Every far tile the client holds or wants (rendering spec §22), render thread only: the server session (from its hello),
 * the disk cache (encoded tiles per server, world and dimension), the requests with the versions held, decoding and
 * meshing on the {@link FarWorkers}, and the meshes' uploads within a budget per frame. Entries no longer wanted are
 * dropped after a few seconds, so memory follows the view, not the distance travelled.
 */
final class TileStore {
    /** Most tiles asked for and not answered yet, and request packets per second. */
    private static final int MAX_IN_FLIGHT = 192, PACKETS_PER_SECOND = 8;
    /** A request unanswered this long is asked again. */
    private static final long REQUEST_TIMEOUT_MS = 20_000;
    /** Mesh jobs at once, and uploads per frame. */
    private static final int MAX_MESH_JOBS = 12, MAX_UPLOADS = 24;
    private static final long MAX_UPLOAD_BYTES = 12L << 20;
    /** An entry not wanted for this long goes (mesh, data). */
    private static final long EVICT_MS = 8_000;
    private static final int FILE_MAGIC = 0x424C4654;

    private final TileColors colors;
    private final Long2ObjectOpenHashMap<TileEntry> entries = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Upload> uploads = new ArrayDeque<>();
    private final List<TileEntry> queue = new ArrayList<>();

    // the server's offer for the level we are in (session -1: none yet)
    private int session = -1;
    private boolean enabled;
    private int serverDistance;
    private int minY = -64;
    @Nullable
    private Path diskRoot;

    private int inFlight, meshJobs, stamps;
    private double packetTokens = PACKETS_PER_SECOND;
    private long lastRequestMs;
    private long gpuBytes;

    private record Upload(TileEntry entry, int stamp, MeshBuilder.Result mesh, int neighbours) {
    }

    TileStore(TileColors colors) {
        this.colors = colors;
    }

    // ---- the server's offer -----------------------------------------------------------------------------------------

    /** A new stay in a level: everything of the last one goes. */
    void hello(FarTerrainPackets.S2CHello hello) {
        clear(false);
        session = hello.session();
        enabled = hello.enabled();
        serverDistance = hello.enabled() ? hello.maxDistance() : 0;
        minY = hello.minY();
        diskRoot = enabled ? diskRoot(hello, "farterrain") : null;
    }

    /** The level went away (disconnect, before the next hello): nothing is valid any more. */
    void forgetSession() {
        clear(false);
        session = -1;
        enabled = false;
        serverDistance = 0;
        diskRoot = null;
    }

    boolean enabled() {
        return session >= 0 && enabled;
    }

    int serverDistance() {
        return serverDistance;
    }

    int minY() {
        return minY;
    }

    /** Where this world's tiles are cached: per server, world (and view limit), registry layout and dimension. */
    @Nullable
    static Path diskRoot(FarTerrainPackets.S2CHello hello, String kind) {
        try {
            Minecraft mc = Minecraft.getInstance();
            String server;
            ServerData data = mc.getCurrentServer();
            if (data != null) server = data.ip;
            else if (mc.getSingleplayerServer() != null) server = "local-" + mc.getSingleplayerServer().getWorldData().getLevelName();
            else server = "unknown";
            String world = Long.toHexString(hello.worldId()) + "-" + Block.BLOCK_STATE_REGISTRY.size();
            return mc.gameDirectory.toPath().resolve("blockland").resolve(kind).resolve(safe(server)).resolve(world)
                    .resolve(safe(hello.dimension()));
        } catch (RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] No disk cache for the far terrain", e);
            return null;
        }
    }

    private static String safe(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return s.isEmpty() ? "_" : s.length() > 80 ? s.substring(0, 80) : s;
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /** Applies what the workers finished and uploads finished meshes (within the frame's budget). */
    void beginFrame() {
        for (int n = 0; n < 4096; n++) {
            Runnable result = results.poll();
            if (result == null) break;
            result.run();
        }
        long bytes = 0;
        for (int n = 0; n < MAX_UPLOADS && !uploads.isEmpty() && bytes < MAX_UPLOAD_BYTES; n++) {
            Upload u = uploads.poll();
            bytes += upload(u);
        }
    }

    /** The entry of a tile the selection wants this frame (created when new). */
    TileEntry want(int level, int tx, int tz, double priority, long now) {
        long key = FarTile.key(level, tx, tz);
        TileEntry e = entries.get(key);
        if (e == null) {
            e = new TileEntry(key);
            entries.put(key, e);
        }
        e.lastWanted = now;
        e.priority = priority;
        return e;
    }

    @Nullable
    TileEntry peek(long key) {
        return entries.get(key);
    }

    /** After the selection: disk reads, requests, mesh jobs, eviction. */
    void schedule(@Nullable ClientLevel level, long now) {
        queue.clear();
        for (Iterator<TileEntry> it = entries.values().iterator(); it.hasNext(); ) {
            TileEntry e = it.next();
            if (now - e.lastWanted > EVICT_MS) {
                evict(e);
                it.remove();
                continue;
            }
            if (e.lastWanted != now) continue;
            if (!e.diskChecked && !e.diskLoading) {
                if (diskRoot == null) e.diskChecked = true;
                else readDisk(e);
            }
            if (e.sentAt != 0 && now - e.sentAt > REQUEST_TIMEOUT_MS) {
                e.sentAt = 0;
                inFlight--;
            }
            if (e.diskChecked && !e.answered && e.sentAt == 0) queue.add(e);
        }
        if (enabled()) sendRequests(now);
        if (level != null) startMeshes(level);
    }

    private void sendRequests(long now) {
        packetTokens = Math.min(PACKETS_PER_SECOND, packetTokens + (now - lastRequestMs) / 1000.0 * PACKETS_PER_SECOND);
        lastRequestMs = now;
        if (queue.isEmpty() || inFlight >= MAX_IN_FLIGHT || packetTokens < 1) return;
        queue.sort((a, b) -> Double.compare(a.priority, b.priority));
        int at = 0;
        while (at < queue.size() && inFlight < MAX_IN_FLIGHT && packetTokens >= 1) {
            int n = Math.min(FarTerrainPackets.MAX_KEYS, Math.min(queue.size() - at, MAX_IN_FLIGHT - inFlight));
            long[] keys = new long[n], versions = new long[n];
            for (int i = 0; i < n; i++) {
                TileEntry e = queue.get(at + i);
                keys[i] = e.key;
                versions[i] = e.empty ? 0 : e.version;
                e.sentAt = now;
                inFlight++;
            }
            at += n;
            packetTokens--;
            BLNetwork.sendToServer(new FarTerrainPackets.C2SRequest(session, keys, versions));
        }
    }

    /** Tells the server to drop whatever it still has queued for us (the view moved away, far terrain off). */
    void cancelRequests() {
        if (session >= 0 && enabled) BLNetwork.sendToServer(new FarTerrainPackets.C2SRequest(session, new long[0], new long[0]));
        for (TileEntry e : entries.values()) e.sentAt = 0;
        inFlight = 0;
    }

    // ---- answers ----------------------------------------------------------------------------------------------------

    void answers(FarTerrainPackets.S2CTiles tiles) {
        if (tiles.session() != session) return;
        for (FarTerrainPackets.Answer a : tiles.answers()) {
            TileEntry e = entries.get(a.key());
            if (e != null && e.sentAt != 0) {
                e.sentAt = 0;
                inFlight = Math.max(0, inFlight - 1);
            }
            switch (a.kind()) {
                case FarTerrainPackets.DATA -> {
                    if (e != null) e.answered = true;
                    decode(a.key(), a.version(), a.data());
                }
                case FarTerrainPackets.UNCHANGED -> {
                    if (e != null) e.answered = true;
                }
                case FarTerrainPackets.EMPTY -> {
                    writeDisk(a.key(), FarTerrainPackets.EMPTY, a.version(), null);
                    if (e != null) {
                        e.answered = true;
                        setEmpty(e, a.version());
                    }
                }
                default -> {
                    if (e != null) {
                        e.answered = true;
                        e.denied = true;
                        e.tile = null;
                        e.dropMesh();
                        e.meshDirty = false;
                    }
                }
            }
        }
    }

    private void decode(long key, long version, byte[] data) {
        int mySession = session;
        double priority = priorityOf(key);
        FarWorkers.submit(priority, () -> {
            FarTile tile;
            try {
                tile = FarTile.decode(data);
            } catch (IllegalArgumentException e) {
                BlockLand.LOGGER.warn("[BlockLand] Malformed far tile from the server: {}", e.getMessage());
                return;
            }
            if (tile.key() != key) return;
            tile.version = version;
            writeDiskNow(key, FarTerrainPackets.DATA, version, data);
            results.add(() -> {
                if (session != mySession) return;
                TileEntry e = entries.get(key);
                if (e != null) setTile(e, tile);
            });
        });
    }

    private void setTile(TileEntry e, FarTile tile) {
        boolean changed = e.tile == null || e.version != tile.version;
        e.tile = tile;
        e.version = tile.version;
        e.empty = false;
        e.denied = false;
        if (changed) {
            e.meshDirty = true;
            touchNeighbours(e);
        }
    }

    private void setEmpty(TileEntry e, long version) {
        e.empty = true;
        e.version = version;
        e.tile = null;
        if (e.mesh != null) {
            gpuBytes -= e.mesh.bytes();
            e.mesh.close();
            e.mesh = null;
        }
        e.meshDirty = false;
        touchNeighbours(e);
    }

    /** The same-level neighbours rebuild their border walls against this tile's new data. */
    private void touchNeighbours(TileEntry e) {
        for (int side = 0; side < 4; side++) {
            TileEntry n = entries.get(FarTile.key(e.level, e.tx + (side == 2 ? -1 : side == 3 ? 1 : 0), e.tz + (side == 0 ? -1 : side == 1 ? 1 : 0)));
            if (n != null && n.tile != null) n.meshDirty = true;
        }
    }

    // ---- the disk cache ---------------------------------------------------------------------------------------------

    private Path file(long key) {
        return diskRoot.resolve(String.valueOf(FarTile.level(key))).resolve(FarTile.tx(key) + "_" + FarTile.tz(key) + ".bin");
    }

    private void readDisk(TileEntry e) {
        e.diskLoading = true;
        long key = e.key;
        int mySession = session;
        Path path = file(key);
        FarWorkers.submit(e.priority, () -> {
            byte kind = -1;
            long version = 0;
            FarTile tile = null;
            if (Files.isRegularFile(path)) {
                try (InputStream raw = Files.newInputStream(path); DataInputStream in = new DataInputStream(raw)) {
                    if (in.readInt() == FILE_MAGIC) {
                        kind = in.readByte();
                        version = in.readLong();
                        if (kind == FarTerrainPackets.DATA) {
                            int length = in.readInt();
                            if (length <= 0 || length > FarTerrainPackets.MAX_TILE_BYTES) throw new IOException("bad length");
                            byte[] data = in.readNBytes(length);
                            tile = FarTile.decode(data);
                            if (tile.key() != key) throw new IOException("wrong tile");
                            tile.version = version;
                        }
                    }
                } catch (IOException | IllegalArgumentException ex) {
                    kind = -1;
                    tile = null;
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // left for the next write to replace
                    }
                }
            }
            byte foundKind = kind;
            long foundVersion = version;
            FarTile found = tile;
            results.add(() -> {
                if (session != mySession) return;
                TileEntry entry = entries.get(key);
                if (entry == null) return;
                entry.diskLoading = false;
                entry.diskChecked = true;
                if (entry.answered) return;
                if (foundKind == FarTerrainPackets.DATA && found != null) setTile(entry, found);
                else if (foundKind == FarTerrainPackets.EMPTY) setEmpty(entry, foundVersion);
            });
        });
    }

    private void writeDisk(long key, byte kind, long version, @Nullable byte[] data) {
        if (diskRoot == null) return;
        FarWorkers.submit(1e9, () -> writeDiskNow(key, kind, version, data));
    }

    /** Worker thread: writes one tile's file (through a temporary file, so a crash never leaves half a tile). */
    private void writeDiskNow(long key, byte kind, long version, @Nullable byte[] data) {
        Path root = diskRoot;
        if (root == null) return;
        Path path = root.resolve(String.valueOf(FarTile.level(key))).resolve(FarTile.tx(key) + "_" + FarTile.tz(key) + ".bin");
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp" + Thread.currentThread().getId());
            try (OutputStream raw = Files.newOutputStream(temp); DataOutputStream out = new DataOutputStream(raw)) {
                out.writeInt(FILE_MAGIC);
                out.writeByte(kind);
                out.writeLong(version);
                if (kind == FarTerrainPackets.DATA && data != null) {
                    out.writeInt(data.length);
                    out.write(data);
                }
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // the cache is only a shortcut; the server still has the tile
        }
    }

    // ---- meshes -----------------------------------------------------------------------------------------------------

    private void startMeshes(ClientLevel level) {
        if (meshJobs >= MAX_MESH_JOBS) return;
        List<TileEntry> dirty = new ArrayList<>();
        for (TileEntry e : entries.values()) {
            if (e.tile != null && e.meshDirty && e.meshJob == 0) dirty.add(e);
        }
        if (dirty.isEmpty()) return;
        dirty.sort((a, b) -> Double.compare(a.priority, b.priority));
        for (TileEntry e : dirty) {
            if (meshJobs >= MAX_MESH_JOBS) break;
            FarTile tile = e.tile;
            TileColors.Palette palette = colors.palette(tile, level);
            int[] mask = new int[1];
            MeshBuilder.Borders borders = new MeshBuilder.Borders(border(e, 0, mask), border(e, 1, mask), border(e, 2, mask),
                    border(e, 3, mask));
            int stamp = ++stamps;
            if (stamp == 0) stamp = ++stamps;
            e.meshJob = stamp;
            e.meshDirty = false;
            meshJobs++;
            long key = e.key;
            int mySession = session, myStamp = stamp, neighbours = mask[0], floor = minY;
            FarWorkers.submit(e.priority, () -> {
                MeshBuilder.Result result;
                try {
                    result = MeshBuilder.build(tile, palette, borders, floor);
                } catch (RuntimeException ex) {
                    BlockLand.LOGGER.warn("[BlockLand] Could not mesh far tile {}", FarTile.level(key), ex);
                    result = new MeshBuilder.Result(null, 0, 0, 0);
                }
                MeshBuilder.Result done = result;
                results.add(() -> {
                    meshJobs--;
                    TileEntry entry = entries.get(key);
                    if (session != mySession || entry == null || entry.meshJob != myStamp) {
                        done.free();
                        return;
                    }
                    uploads.add(new Upload(entry, myStamp, done, neighbours));
                });
            });
        }
    }

    /** A same-level neighbour's border heights along the shared edge (0 north, 1 south, 2 west, 3 east), or null. */
    @Nullable
    private short[] border(TileEntry e, int side, int[] mask) {
        int dx = side == 2 ? -1 : side == 3 ? 1 : 0, dz = side == 0 ? -1 : side == 1 ? 1 : 0;
        TileEntry n = entries.get(FarTile.key(e.level, e.tx + dx, e.tz + dz));
        if (n == null || n.tile == null && !n.empty) return null;
        mask[0] |= 1 << side;
        short[] edge = new short[FarTile.SIZE];
        if (n.tile == null) {
            java.util.Arrays.fill(edge, FarTile.EMPTY);
            return edge;
        }
        for (int k = 0; k < FarTile.SIZE; k++) {
            int i = side == 2 ? FarTile.SIZE - 1 : side == 3 ? 0 : k;
            int j = side == 0 ? FarTile.SIZE - 1 : side == 1 ? 0 : k;
            edge[k] = n.tile.height[FarTile.index(i, j)];
        }
        return edge;
    }

    /** Puts a finished mesh on the GPU; returns its bytes. */
    private long upload(Upload u) {
        TileEntry e = u.entry;
        if (entries.get(e.key) != e || e.meshJob != u.stamp) {
            u.mesh.free();
            return 0;
        }
        GpuBuffer buffer = null;
        long bytes = 0;
        if (u.mesh.quads > 0 && u.mesh.data != null) {
            bytes = u.mesh.data.remaining();
            int quads = u.mesh.quads;
            String label = "BlockLand far tile " + e.level;
            buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, u.mesh.data);
            if (buffer.size() < (long) quads * 4 * FarVertex.BYTES) {
                buffer.close();
                buffer = null;
            }
        }
        if (e.mesh != null) {
            gpuBytes -= e.mesh.bytes();
            e.mesh.close();
        }
        e.mesh = new FarMesh(buffer, buffer == null ? 0 : u.mesh.quads, u.mesh.minY, u.mesh.maxY);
        gpuBytes += e.mesh.bytes();
        e.neighbours = u.neighbours;
        e.meshJob = 0;
        u.mesh.free();
        return bytes;
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------------

    /** The block atlas changed (resource reload): every mesh is built again with the new colours. */
    void recolour() {
        colors.clear();
        for (TileEntry e : entries.values()) if (e.tile != null) e.meshDirty = true;
    }

    private void evict(TileEntry e) {
        if (e.mesh != null) {
            gpuBytes -= e.mesh.bytes();
            e.mesh.close();
            e.mesh = null;
        }
        if (e.sentAt != 0) inFlight = Math.max(0, inFlight - 1);
        e.tile = null;
    }

    /** Frees every mesh and tile; {@code tellServer}: also drop what the server has queued for us. */
    void clear(boolean tellServer) {
        if (tellServer) cancelRequests();
        for (TileEntry e : entries.values()) {
            if (e.mesh != null) e.mesh.close();
        }
        entries.clear();
        for (Upload u : uploads) u.mesh.free();
        uploads.clear();
        // results still in flight belong to the old session: they free themselves when they find nothing
        gpuBytes = 0;
        inFlight = 0;
    }

    private double priorityOf(long key) {
        TileEntry e = entries.get(key);
        return e == null ? 1e6 : e.priority;
    }

    // ---- statistics -------------------------------------------------------------------------------------------------

    int held() {
        int n = 0;
        for (TileEntry e : entries.values()) if (e.tile != null) n++;
        return n;
    }

    long gpuBytes() {
        return gpuBytes;
    }

    int pending() {
        return inFlight + meshJobs + uploads.size();
    }
}
