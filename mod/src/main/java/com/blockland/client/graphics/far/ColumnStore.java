package com.blockland.client.graphics.far;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarColumn;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.FarChunkPackets;
import com.blockland.network.packet.FarTerrainPackets;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The real far chunks the client holds or wants (rendering spec §23), render thread only. What is wanted is a quadtree of
 * units round the camera's chunk: chunks (scale 0) out to the full-detail distance, beyond it units of 2^k × 2^k chunks
 * whose blocks are cubes of 2^k real blocks, the scale doubling with the distance, out to the far chunk distance; chunks
 * vanilla draws itself are left to vanilla. Units are asked for nearest first with the version held, cached on disk, kept
 * as their compressed bytes (their sections decoded on the {@link FarWorkers} only while a mesh needs them), meshed once
 * their neighbours of the same scale are known, and placed in the {@link RegionRenderer}. A unit no longer wanted stays
 * drawn until every unit replacing it is ready, then they swap in one frame — real terrain never gives way to the far
 * tiles while its replacement loads. The far tiles fill only what no unit holds ({@link CoverageMask}).
 */
final class ColumnStore implements CoverageMask.Units {
    private static final int MAX_IN_FLIGHT = 1024, PACKETS_PER_SECOND = 20;
    private static final long REQUEST_TIMEOUT_MS = 60_000, EVICT_MS = 5_000, BAND_MS = 250, SCHEDULE_MS = 50;
    /** A unit replaced by others is drawn at most this long while they load (then the far tiles fill in). */
    private static final long LINGER_MS = 20_000, LINGER_CHECK_MS = 100;
    private static final int MAX_MESH_JOBS = 24, MAX_PLACES = 64;
    private static final long MAX_PLACE_BYTES = 24L << 20;
    private static final int FILE_MAGIC = 0x424C4644;

    /** One unit. */
    static final class Entry {
        final long key;
        final int scale, x, z;
        /**
         * It has real chunks ({@code has}): the unit as the server sent it (compressed) in {@code data} until it is drawn and
         * on disk ({@code onDisk}), then read from the disk again when a mesh needs it; its sections and light only while a
         * mesh needs them.
         */
        boolean has, onDisk;
        @Nullable
        byte[] data;
        @Nullable
        ColumnGetter.Snapshot snapshot;
        /** Which of its chunks exist (null: all), from the data. */
        @Nullable
        long[] present;
        long version;
        boolean empty, denied, answered, diskChecked, diskLoading, decoding;
        long sentAt;
        /** Placed on the GPU; must be meshed (again); the mesh job in flight (stamp); a mesh waiting to be placed. */
        boolean placed, meshDirty;
        int meshJob;
        @Nullable
        ColumnMesher.Result ready;
        /** In the quadtree now (else since {@code leftAt}); {@code mark} is the layout's scratch. */
        boolean wanted, mark;
        long leftAt;
        double priority;

        Entry(long key) {
            this.key = key;
            this.scale = FarColumn.scale(key);
            this.x = FarColumn.x(key);
            this.z = FarColumn.z(key);
        }

        boolean known() {
            return has || empty && answered || denied;
        }

        /** Nothing more to come for it: drawn, ready to be, or nothing to draw. */
        boolean settled() {
            return placed && !meshDirty && meshJob == 0 || ready != null || known() && !has;
        }

        int chunkX() {
            return x << scale;
        }

        int chunkZ() {
            return z << scale;
        }

        boolean overlaps(Entry o) {
            int s = 1 << scale, os = 1 << o.scale;
            return chunkX() < o.chunkX() + os && o.chunkX() < chunkX() + s && chunkZ() < o.chunkZ() + os && o.chunkZ() < chunkZ() + s;
        }
    }

    private final RegionRenderer regions;
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
    private final ObjectOpenHashSet<Entry> placed = new ObjectOpenHashSet<>(), lingering = new ObjectOpenHashSet<>();
    private final List<Entry> readyList = new ArrayList<>();
    private final List<Entry> queue = new ArrayList<>(), dirty = new ArrayList<>();
    private int session = -1;
    private boolean enabled;
    @Nullable
    private Path diskRoot;
    private int inFlight, meshJobs, stamps;
    /** Bumped by {@link #clear}: work started before belongs to nothing. */
    private int epoch, held;
    private double packetTokens = PACKETS_PER_SECOND;
    private long lastRequestMs, lingerChecked;
    /** The quadtree as last laid out: its centre chunk and radii (chunks). */
    private int ringX = Integer.MIN_VALUE, ringZ, ringInner, ringFull, ringOuter, renderDistance;
    private long bandChecked, scheduled, snapshotsDropped;
    /** The level's lowest block and height. */
    private int minY, height;
    /** What is placed changed since the coverage mask last looked. */
    boolean coverageChanged;

    ColumnStore(RegionRenderer regions) {
        this.regions = regions;
    }

    void hello(FarTerrainPackets.S2CHello hello) {
        clear(false);
        session = hello.session();
        enabled = hello.enabled();
        diskRoot = enabled ? TileStore.diskRoot(hello, "farchunks2") : null;
    }

    void forgetSession() {
        clear(false);
        session = -1;
        enabled = false;
        diskRoot = null;
    }

    boolean enabled() {
        return session >= 0 && enabled;
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /** What the workers finished, then the meshes placed (and units swapped) within the frame's budget. */
    void beginFrame(long now) {
        for (int n = 0; n < 8192; n++) {
            Runnable r = results.poll();
            if (r == null) break;
            r.run();
        }
        if (now - lingerChecked >= LINGER_CHECK_MS && !lingering.isEmpty()) {
            lingerChecked = now;
            swaps(now);
        }
        if (readyList.isEmpty()) return;
        readyList.sort((a, b) -> Double.compare(a.priority, b.priority));
        long bytes = 0;
        int count = 0, looked = 0;
        for (Iterator<Entry> it = readyList.iterator(); it.hasNext() && count < MAX_PLACES && bytes < MAX_PLACE_BYTES
                && looked++ < 1024; ) {
            Entry e = it.next();
            if (e.ready == null || !e.wanted) {
                it.remove();
                continue;
            }
            // a unit still drawn where this one goes: they swap together once all of its replacements are ready
            if (blocked(e)) continue;
            bytes += e.ready.bytes();
            count++;
            it.remove();
            placeReady(e);
        }
    }

    private boolean blocked(Entry e) {
        for (Entry l : lingering) if (l.overlaps(e)) return true;
        return false;
    }

    /** Units drawn but no longer wanted go once every wanted unit over them is settled; the ready ones take their place. */
    private void swaps(long now) {
        List<Entry> over = new ArrayList<>();
        for (Entry l : new ArrayList<>(lingering)) {
            if (!lingering.contains(l)) continue;
            over.clear();
            boolean settled = wantedOver(l, over);
            if (!settled && now - l.leftAt < LINGER_MS) continue;
            removePlaced(l);
            for (Entry w : over) {
                if (w.ready == null || blocked(w)) continue;
                readyList.remove(w);
                placeReady(w);
            }
        }
    }

    /** The wanted units over a lingering one (an ancestor, or its descendants down the tree); whether all are settled. */
    private boolean wantedOver(Entry l, List<Entry> out) {
        for (int s = l.scale + 1; s <= FarColumn.MAX_SCALE; s++) {
            Entry a = entries.get(FarColumn.unitOf(s, l.chunkX(), l.chunkZ()));
            if (a != null && a.wanted) {
                out.add(a);
                return a.settled();
            }
        }
        return l.scale == 0 || descend(l.key, out);
    }

    private boolean descend(long key, List<Entry> out) {
        boolean settled = true;
        for (int i = 0; i < 4; i++) {
            long c = FarColumn.child(key, i);
            Entry e = entries.get(c);
            if (e != null && e.wanted) {
                out.add(e);
                settled &= e.settled();
            } else if (FarColumn.scale(c) > 0) {
                settled &= descend(c, out);
            }
        }
        return settled;
    }

    private void placeReady(Entry e) {
        ColumnMesher.Result mesh = e.ready;
        e.ready = null;
        if (mesh == null) return;
        regions.place(e.key, Math.floorDiv(minY, 1 << e.scale) << e.scale, mesh);
        e.placed = true;
        placed.add(e);
        lingering.remove(e);
        coverageChanged = true;
    }

    private void removePlaced(Entry e) {
        if (e.placed) regions.remove(e.key);
        e.placed = false;
        placed.remove(e);
        lingering.remove(e);
        coverageChanged = true;
        if (e.has) e.meshDirty = true;
    }

    @Override
    public void forEach(CoverageMask.Visitor visitor) {
        for (Entry e : placed) visitor.unit(e.key, e.present);
    }

    /**
     * The quadtree round the camera's chunk, laid out again when the camera changes chunk or the radii change; the band along
     * vanilla's edge (its chunks come and go) is looked at again every {@link #BAND_MS}.
     *
     * @param renderDistance vanilla's render distance (chunks)
     * @param full           chunks are drawn block for block out to this many chunks (coarser cubes beyond)
     * @param outer          the far chunk distance (chunks)
     */
    void frame(ClientLevel level, int camChunkX, int camChunkZ, int renderDistance, int full, int outer, long now) {
        minY = level.getMinY();
        height = level.getHeight();
        this.renderDistance = renderDistance;
        int inner = Math.max(0, renderDistance - 2);
        full = Math.max(full, renderDistance + 4);
        if (camChunkX != ringX || camChunkZ != ringZ || inner != ringInner || full != ringFull || outer != ringOuter) {
            ringX = camChunkX;
            ringZ = camChunkZ;
            ringInner = inner;
            ringFull = full;
            ringOuter = outer;
            bandChecked = now;
            for (Entry e : entries.values()) e.mark = false;
            LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int top = FarColumn.MAX_SCALE, side = 1 << top;
            int x0 = Math.floorDiv(camChunkX - outer, side), x1 = Math.floorDiv(camChunkX + outer, side);
            int z0 = Math.floorDiv(camChunkZ - outer, side), z1 = Math.floorDiv(camChunkZ + outer, side);
            for (int uz = z0; uz <= z1; uz++) {
                for (int ux = x0; ux <= x1; ux++) visit(level, renderer, pos, top, ux, uz);
            }
            for (Entry e : entries.values()) setWanted(e, e.mark, now);
            scheduled = 0;
        } else if (now - bandChecked >= BAND_MS) {
            bandChecked = now;
            LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int band = Math.min(renderDistance + 3, outer);
            long in2 = (long) inner * inner, band2 = (long) band * band;
            for (int dz = -band; dz <= band; dz++) {
                for (int dx = -band; dx <= band; dx++) {
                    long d2 = (long) dx * dx + (long) dz * dz;
                    if (d2 <= in2 || d2 > band2) continue;
                    int cx = camChunkX + dx, cz = camChunkZ + dz;
                    long key = FarColumn.key(0, cx, cz);
                    boolean want = !CoverageMask.vanillaDraws(level, renderer, camChunkX, camChunkZ, renderDistance, cx, cz, pos);
                    Entry e = entries.get(key);
                    if (e == null) {
                        if (!want) continue;
                        e = new Entry(key);
                        entries.put(key, e);
                        e.priority = d2;
                    }
                    setWanted(e, want, now);
                    // vanilla draws it now: off at once, never under vanilla's own chunk
                    if (!want && e.placed) removePlaced(e);
                }
            }
        }
        if (now - scheduled >= SCHEDULE_MS) {
            scheduled = now;
            schedule(level, now);
        }
    }

    /** One node: beyond the far distance or inside vanilla's area nothing; split while nearer than its scale allows. */
    private void visit(ClientLevel level, LevelRenderer renderer, BlockPos.MutableBlockPos pos, int scale, int ux, int uz) {
        int side = 1 << scale, x0 = ux << scale, z0 = uz << scale;
        long ndx = Math.max(0, Math.max(x0 - ringX, ringX - (x0 + side - 1)));
        long ndz = Math.max(0, Math.max(z0 - ringZ, ringZ - (z0 + side - 1)));
        long fdx = Math.max(Math.abs(x0 - ringX), Math.abs(x0 + side - 1 - ringX));
        long fdz = Math.max(Math.abs(z0 - ringZ), Math.abs(z0 + side - 1 - ringZ));
        long near2 = ndx * ndx + ndz * ndz, far2 = fdx * fdx + fdz * fdz;
        if (near2 > (long) ringOuter * ringOuter || far2 <= (long) ringInner * ringInner) return;
        if (scale > 0) {
            long limit = (long) ringFull << (scale - 1);
            if (near2 < limit * limit) {
                for (int i = 0; i < 4; i++) visit(level, renderer, pos, scale - 1, ux * 2 + (i & 1), uz * 2 + (i >> 1));
                return;
            }
        } else if (near2 <= (long) (renderDistance + 3) * (renderDistance + 3)
                && CoverageMask.vanillaDraws(level, renderer, ringX, ringZ, renderDistance, ux, uz, pos)) {
            return;
        }
        long key = FarColumn.key(scale, ux, uz);
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry(key);
            entries.put(key, e);
        }
        e.mark = true;
        e.priority = near2;
    }

    private void setWanted(Entry e, boolean want, long now) {
        if (e.wanted == want) return;
        e.wanted = want;
        if (want) {
            lingering.remove(e);
            return;
        }
        e.leftAt = now;
        if (e.ready != null) {
            e.ready.free();
            e.ready = null;
        }
        if (e.placed) lingering.add(e);
        else if (e.has) e.meshDirty = true;
    }

    private void schedule(ClientLevel level, long now) {
        queue.clear();
        dirty.clear();
        int count = 0;
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.has) count++;
            if (!e.wanted) {
                if (!e.placed && now - e.leftAt > EVICT_MS) {
                    if (e.sentAt != 0) inFlight = Math.max(0, inFlight - 1);
                    it.remove();
                }
                continue;
            }
            if (!e.diskChecked && !e.diskLoading) {
                if (diskRoot == null) e.diskChecked = true;
                else readDisk(e);
            }
            if (e.sentAt != 0 && now - e.sentAt > REQUEST_TIMEOUT_MS) {
                e.sentAt = 0;
                inFlight = Math.max(0, inFlight - 1);
            }
            if (e.diskChecked && !e.answered && e.sentAt == 0) queue.add(e);
            if (e.has && (e.meshDirty || !e.placed) && e.meshJob == 0 && e.ready == null) dirty.add(e);
        }
        held = count;
        if (now - snapshotsDropped >= 500) {
            snapshotsDropped = now;
            dropSnapshots();
        }
        if (enabled()) sendRequests(now);
        startMeshes(level, dirty);
    }

    private void sendRequests(long now) {
        packetTokens = Math.min(PACKETS_PER_SECOND, packetTokens + (now - lastRequestMs) / 1000.0 * PACKETS_PER_SECOND);
        lastRequestMs = now;
        if (queue.isEmpty() || inFlight >= MAX_IN_FLIGHT || packetTokens < 1) return;
        queue.sort((a, b) -> Double.compare(a.priority, b.priority));
        int at = 0;
        while (at < queue.size() && inFlight < MAX_IN_FLIGHT && packetTokens >= 1) {
            int n = Math.min(FarChunkPackets.MAX_KEYS, Math.min(queue.size() - at, MAX_IN_FLIGHT - inFlight));
            long[] keys = new long[n], versions = new long[n];
            for (int i = 0; i < n; i++) {
                Entry e = queue.get(at + i);
                keys[i] = e.key;
                versions[i] = e.empty ? 0 : e.version;
                e.sentAt = now;
                inFlight++;
            }
            at += n;
            packetTokens--;
            BLNetwork.sendToServer(new FarChunkPackets.C2SRequest(session, keys, versions));
        }
    }

    void cancelRequests() {
        if (enabled()) BLNetwork.sendToServer(new FarChunkPackets.C2SRequest(session, new long[0], new long[0]));
        for (Entry e : entries.values()) e.sentAt = 0;
        inFlight = 0;
    }

    // ---- answers ----------------------------------------------------------------------------------------------------

    void answers(FarChunkPackets.S2CColumns columns, @Nullable ClientLevel level) {
        if (columns.session() != session || level == null) return;
        for (FarTerrainPackets.Answer a : columns.answers()) {
            Entry e = entries.get(a.key());
            if (e != null && e.sentAt != 0) {
                e.sentAt = 0;
                inFlight = Math.max(0, inFlight - 1);
            }
            if (e != null) e.answered = true;
            switch (a.kind()) {
                case FarTerrainPackets.DATA -> decode(a.key(), a.data(), level, true, false);
                case FarTerrainPackets.UNCHANGED -> {
                }
                case FarTerrainPackets.EMPTY -> {
                    writeDisk(a.key(), FarTerrainPackets.EMPTY, a.version(), null);
                    if (e != null) setNothing(e, false);
                }
                default -> {
                    if (e != null) setNothing(e, true);
                }
            }
        }
    }

    /** No real chunk there (or not for us): nothing drawn, the far tiles fill it. */
    private void setNothing(Entry e, boolean denied) {
        if (denied) e.denied = true;
        else e.empty = true;
        e.has = e.onDisk = false;
        e.data = null;
        e.snapshot = null;
        e.present = null;
        if (e.ready != null) {
            e.ready.free();
            e.ready = null;
        }
        if (e.placed) removePlaced(e);
        touchNeighbours(e);
    }

    /**
     * Worker: the unit's bytes into Minecraft's sections (with the level's biome registry). {@code again}: the bytes the
     * entry already holds, decoded once more for a mesh (nothing about the unit changed).
     */
    private void decode(long key, byte[] data, ClientLevel level, boolean fromServer, boolean again) {
        int myEpoch = epoch;
        Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Entry at = entries.get(key);
        double priority = at == null ? 1e9 : at.priority;
        FarWorkers.submit(priority, () -> {
            ColumnGetter.Snapshot snapshot;
            long version;
            long[] present;
            try {
                FarColumn column = FarColumn.decode(data);
                if (column.key() != key) throw new IllegalArgumentException("far chunk for another place");
                version = column.version;
                present = column.present;
                snapshot = snapshot(column, biomes);
            } catch (RuntimeException ex) {
                BlockLand.LOGGER.warn("[BlockLand] Malformed far chunk: {}", ex.toString());
                results.add(() -> {
                    Entry e = entries.get(key);
                    if (epoch != myEpoch || e == null) return;
                    e.decoding = false;
                });
                return;
            }
            boolean saved = fromServer && writeDiskNow(key, FarTerrainPackets.DATA, version, data);
            results.add(() -> {
                if (epoch != myEpoch) return;
                Entry e = entries.get(key);
                if (e == null) return;
                if (again) {
                    e.decoding = false;
                    if (e.has && e.version == version) e.snapshot = snapshot;
                    return;
                }
                boolean changed = !e.has || e.version != version;
                e.has = true;
                e.onDisk = !fromServer || saved;
                e.data = data;
                e.snapshot = snapshot;
                e.present = present;
                e.version = version;
                e.empty = e.denied = false;
                if (changed) {
                    e.meshDirty = true;
                    if (e.placed) coverageChanged = true;
                    touchNeighbours(e);
                }
            });
        });
    }

    private static ColumnGetter.Snapshot snapshot(FarColumn column, Registry<Biome> biomes) {
        int n = column.sections.length;
        LevelChunkSection[] sections = new LevelChunkSection[n];
        int top = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            byte[] bytes = column.sections[i];
            if (bytes == null) continue;
            LevelChunkSection section = new LevelChunkSection(biomes);
            section.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)));
            if (section.hasOnlyAir()) continue;
            sections[i] = section;
            top = ((column.minSection + i) << 4) + 15;
        }
        return new ColumnGetter.Snapshot(column.cx, column.cz, column.minSection, sections, shared(column.skyLight),
                shared(column.blockLight), top);
    }

    private static final byte[] DARK = new byte[2048], BRIGHT = new byte[2048];

    static {
        Arrays.fill(BRIGHT, (byte) -1);
    }

    /** Light arrays all 0 or all 15 (most of them: underground, open sky, no lamps) as one shared array each. */
    private static byte[][] shared(byte[][] light) {
        for (int i = 0; i < light.length; i++) {
            byte[] a = light[i];
            if (a == null || a.length != 2048) continue;
            byte first = a[0];
            if (first != 0 && first != -1) continue;
            boolean same = true;
            for (int k = 1; k < a.length && same; k++) same = a[k] == first;
            if (same) light[i] = first == 0 ? DARK : BRIGHT;
        }
        return light;
    }

    /** A chunk vanilla holds (at the edge of its area), as a neighbour: its sections and light copied now. */
    @Nullable
    private static ColumnGetter.Snapshot vanilla(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) return null;
        LevelChunkSection[] sections = chunk.getSections();
        int min = level.getMinSectionY();
        byte[][] sky = new byte[sections.length][], block = new byte[sections.length][];
        LevelChunkSection[] copy = new LevelChunkSection[sections.length];
        int top = Integer.MIN_VALUE;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null || sections[i].hasOnlyAir()) continue;
            copy[i] = sections[i].copy();
            top = ((min + i) << 4) + 15;
            DataLayer s = level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(cx, min + i, cz));
            DataLayer b = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(cx, min + i, cz));
            if (s != null) sky[i] = s.copy().getData();
            if (b != null) block[i] = b.copy().getData();
        }
        return new ColumnGetter.Snapshot(cx, cz, min, copy, shared(sky), shared(block), top);
    }

    /** The unit's data changed: its neighbours of the same scale are meshed again (their border faces depend on it). */
    private void touchNeighbours(Entry e) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                Entry n = entries.get(FarColumn.key(e.scale, e.x + dx, e.z + dz));
                if (n != null && n.has) n.meshDirty = true;
            }
        }
    }

    // ---- meshes -----------------------------------------------------------------------------------------------------

    /** Vanilla chunks copied as neighbours, kept a moment (several units along vanilla's edge share each). */
    private record VanillaCopy(@Nullable ColumnGetter.Snapshot snapshot, long at) {
    }

    private final Long2ObjectOpenHashMap<VanillaCopy> vanillaCopies = new Long2ObjectOpenHashMap<>();
    /** Vanilla chunks copied per look at the meshes, and the render thread's time for one look (nanoseconds). */
    private static final int MAX_VANILLA_COPIES = 12;
    private static final long MESH_START_NANOS = 2_000_000;

    private void startMeshes(ClientLevel level, List<Entry> dirty) {
        long now = System.currentTimeMillis();
        vanillaCopies.values().removeIf(c -> now - c.at > 2000);
        if (dirty.isEmpty() || meshJobs >= MAX_MESH_JOBS) return;
        dirty.sort((a, b) -> Double.compare(a.priority, b.priority));
        Holder<Biome> plains = level.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).orElse(null);
        long deadline = System.nanoTime() + MESH_START_NANOS;
        int copies = 0;
        for (Entry e : dirty) {
            if (meshJobs >= MAX_MESH_JOBS || System.nanoTime() > deadline) break;
            if (e.snapshot == null) {
                hydrate(e, level);
                continue;
            }
            // first whether every neighbour is there (nothing copied for a unit that has to wait)
            boolean ready = true;
            int needCopies = 0;
            for (int i = 0; i < 9 && ready; i++) {
                if (i == 4) continue;
                int dx = i % 3 - 1, dz = i / 3 - 1;
                Entry n = entries.get(FarColumn.key(e.scale, e.x + dx, e.z + dz));
                if (n != null && n.snapshot != null) continue;
                if (n != null && n.has) {
                    // held but not decoded: decoded again, the mesh waits for it
                    hydrate(n, level);
                    ready = false;
                } else if (n != null && n.wanted && !n.known()) {
                    // a neighbour on its way: wait for it, its faces decide this unit's border
                    ready = false;
                } else if (e.scale == 0 && !vanillaCopies.containsKey(ChunkKey.of(e.x + dx, e.z + dz))) {
                    needCopies++;
                }
            }
            if (!ready || copies + needCopies > MAX_VANILLA_COPIES && needCopies > 0) continue;
            ColumnGetter.Snapshot[] around = new ColumnGetter.Snapshot[9];
            around[4] = e.snapshot;
            for (int i = 0; i < 9; i++) {
                if (i == 4) continue;
                int dx = i % 3 - 1, dz = i / 3 - 1;
                Entry n = entries.get(FarColumn.key(e.scale, e.x + dx, e.z + dz));
                if (n != null && n.snapshot != null) {
                    around[i] = n.snapshot;
                } else if (e.scale == 0 && (n == null || !n.has)) {
                    // vanilla's own chunk (along its edge), else air (beyond the ring)
                    long ck = ChunkKey.of(e.x + dx, e.z + dz);
                    VanillaCopy copy = vanillaCopies.get(ck);
                    if (copy == null) {
                        copy = new VanillaCopy(vanilla(level, e.x + dx, e.z + dz), now);
                        vanillaCopies.put(ck, copy);
                        copies++;
                    }
                    around[i] = copy.snapshot;
                }
                // a unit of another scale there: air, so this one's border is closed by its own faces
            }
            int scale = e.scale;
            int gridMin = FarColumn.minSection(minY, scale) * 16, gridHeight = FarColumn.sectionCount(minY, height, scale) * 16;
            ColumnGetter getter = new ColumnGetter(around, level, gridMin, gridHeight, plains);
            int stamp = ++stamps;
            if (stamp == 0) stamp = ++stamps;
            e.meshJob = stamp;
            e.meshDirty = false;
            meshJobs++;
            int myEpoch = epoch, myStamp = stamp;
            ColumnGetter.Snapshot column = e.snapshot;
            int ox = RegionRenderer.originOf(RegionRenderer.regionOf(e.x)), oz = RegionRenderer.originOf(RegionRenderer.regionOf(e.z));
            int oy = Math.floorDiv(minY, 1 << scale);
            FarWorkers.submit(e.priority, () -> {
                ColumnMesher.Result result;
                try {
                    result = ColumnMesher.mesh(getter, column, scale, ox, oy, oz);
                } catch (RuntimeException ex) {
                    BlockLand.LOGGER.warn("[BlockLand] Could not mesh far chunk {} {} (scale {})", column.cx(), column.cz(), scale, ex);
                    result = new ColumnMesher.Result();
                }
                ColumnMesher.Result done = result;
                results.add(() -> {
                    if (epoch != myEpoch) {
                        done.free();
                        return;
                    }
                    meshJobs--;
                    Entry entry = entries.get(e.key);
                    if (entry != e || e.meshJob != myStamp) {
                        done.free();
                        return;
                    }
                    e.meshJob = 0;
                    if (!e.wanted) {
                        done.free();
                        return;
                    }
                    if (e.ready != null) e.ready.free();
                    else readyList.add(e);
                    e.ready = done;
                });
            });
        }
    }

    private void hydrate(Entry e, ClientLevel level) {
        if (e.decoding || !e.has) return;
        e.decoding = true;
        if (e.data != null) {
            decode(e.key, e.data, level, false, true);
            return;
        }
        // dropped from memory: the disk has it
        long key = e.key, version = e.version;
        int myEpoch = epoch;
        Path path = diskRoot == null ? null : file(diskRoot, key);
        FarWorkers.submit(e.priority, () -> {
            byte[] bytes = path == null ? null : readData(path, version);
            results.add(() -> {
                Entry entry = entries.get(key);
                if (epoch != myEpoch || entry == null) return;
                if (bytes == null) {
                    // the file went: ask the server again
                    entry.decoding = false;
                    entry.has = entry.onDisk = false;
                    entry.answered = false;
                    entry.version = 0;
                    return;
                }
                decode(key, bytes, level, false, true);
            });
        });
    }

    /**
     * Sections and light no mesh needs any more are let go (the bytes stay): a unit drawn whose every neighbour of its scale
     * is drawn too, has nothing to draw, or is not wanted.
     */
    private void dropSnapshots() {
        for (Entry e : entries.values()) {
            if (e.snapshot == null || !done(e)) continue;
            boolean all = true;
            for (int dz = -1; dz <= 1 && all; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    Entry n = entries.get(FarColumn.key(e.scale, e.x + dx, e.z + dz));
                    if (n != null && n.wanted && !(n.known() && (!n.has || done(n)))) {
                        all = false;
                        break;
                    }
                }
            }
            if (all) {
                e.snapshot = null;
                // on disk too: the bytes go as well (read again if a mesh ever needs them)
                if (e.onDisk) e.data = null;
            }
        }
    }

    private static boolean done(Entry e) {
        return !e.wanted || e.placed && !e.meshDirty && e.meshJob == 0 && e.ready == null;
    }

    /** Chunk keys for the vanilla copies. */
    private static final class ChunkKey {
        static long of(int x, int z) {
            return (long) x & 0xFFFFFFFFL | (long) z << 32;
        }
    }

    // ---- the disk cache ---------------------------------------------------------------------------------------------

    private static Path file(Path root, long key) {
        int x = FarColumn.x(key), z = FarColumn.z(key);
        return root.resolve("s" + FarColumn.scale(key)).resolve((x >> 5) + "." + (z >> 5)).resolve(x + "_" + z + ".bin");
    }

    private void readDisk(Entry e) {
        e.diskLoading = true;
        long key = e.key;
        int myEpoch = epoch;
        Path path = file(diskRoot, key);
        FarWorkers.submit(e.priority, () -> {
            byte kind = -1;
            long version = 0;
            byte[] data = null;
            if (Files.isRegularFile(path)) {
                try (InputStream raw = Files.newInputStream(path); DataInputStream in = new DataInputStream(raw)) {
                    if (in.readInt() == FILE_MAGIC) {
                        kind = in.readByte();
                        version = in.readLong();
                        if (kind == FarTerrainPackets.DATA) {
                            int length = in.readInt();
                            if (length <= 0 || length > FarChunkPackets.MAX_COLUMN_BYTES) throw new IOException("bad length");
                            data = in.readNBytes(length);
                        }
                    }
                } catch (IOException ex) {
                    kind = -1;
                }
            }
            byte foundKind = kind;
            long foundVersion = version;
            byte[] found = data;
            results.add(() -> {
                if (epoch != myEpoch) return;
                Entry entry = entries.get(key);
                if (entry == null) return;
                entry.diskLoading = false;
                entry.diskChecked = true;
                if (entry.answered) return;
                if (foundKind == FarTerrainPackets.DATA && found != null) {
                    entry.version = foundVersion;
                    ClientLevel level = Minecraft.getInstance().level;
                    if (level != null) decode(key, found, level, false, false);
                } else if (foundKind == FarTerrainPackets.EMPTY) {
                    // only a guess until the server answers (the chunks may exist by now): nothing drawn, nothing waited for
                    entry.version = foundVersion;
                    entry.empty = true;
                }
            });
        });
    }

    private void writeDisk(long key, byte kind, long version, @Nullable byte[] data) {
        if (diskRoot == null) return;
        FarWorkers.submit(1e12, () -> {
            writeDiskNow(key, kind, version, data);
        });
    }

    /** Whether the file was written. */
    private boolean writeDiskNow(long key, byte kind, long version, @Nullable byte[] data) {
        Path root = diskRoot;
        if (root == null) return false;
        Path path = file(root, key);
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
            return true;
        } catch (IOException | RuntimeException ex) {
            // only a shortcut: the server still has the unit
            return false;
        }
    }

    /** A unit's bytes from its file when it holds this version, else null (worker). */
    @Nullable
    private static byte[] readData(Path path, long version) {
        try (InputStream raw = Files.newInputStream(path); DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != FILE_MAGIC || in.readByte() != FarTerrainPackets.DATA || in.readLong() != version) return null;
            int length = in.readInt();
            if (length <= 0 || length > FarChunkPackets.MAX_COLUMN_BYTES) return null;
            return in.readNBytes(length);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------------

    /** The block atlas changed: every unit is meshed again. */
    void remesh() {
        for (Entry e : entries.values()) if (e.has) e.meshDirty = true;
    }

    void clear(boolean tellServer) {
        if (tellServer) cancelRequests();
        for (Entry e : entries.values()) if (e.ready != null) e.ready.free();
        entries.clear();
        readyList.clear();
        placed.clear();
        lingering.clear();
        regions.clear();
        vanillaCopies.clear();
        inFlight = meshJobs = held = 0;
        epoch++;
        ringX = Integer.MIN_VALUE;
        coverageChanged = true;
    }

    /** Units held in memory (as of the last look over them). */
    int held() {
        return held;
    }

    int pending() {
        return inFlight + meshJobs + readyList.size();
    }

    int placedCount() {
        return placed.size();
    }
}
