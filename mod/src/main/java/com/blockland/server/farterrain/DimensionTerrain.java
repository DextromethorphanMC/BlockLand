package com.blockland.server.farterrain;

import com.blockland.BlockLand;
import com.blockland.farterrain.FarTile;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Far terrain of one dimension on the server (rendering spec §22): its tiles (memory LRU over the disk cache), which
 * chunks it knows for real ({@link RealIndex}), where saved chunks are ({@link RegionIndex}), the generator's estimate,
 * and the work that keeps tiles true — merging captured chunks into level-0 tiles, copying real cells into coarser tiles
 * whose sampled chunk changed since they were built, importing saved chunks a tile needs, estimating the rest. All of it
 * runs on {@link Workers}; the server thread only hands captures in and asks for tiles.
 */
final class DimensionTerrain {
    /** Tiles kept in memory per dimension. */
    private static final int CACHE = 1024;
    /** An entry used this recently is never evicted (a worker may still be filling it). */
    private static final long KEEP_MS = 15_000;
    /** Saved chunks read at once through the server's chunk storage (it also serves the players' own chunk loads). */
    private static final int IMPORTS_IN_FLIGHT = 24;
    /** Tiles of this level and coarser never wait for saved chunks: estimated now, refreshed once the chunks are read. */
    private static final int NO_WAIT_LEVEL = 3;
    /** Most saved chunks one tile waits for; the rest are estimated meanwhile and imported for its next refresh. */
    private static final int IMPORTS_PER_TILE = 96;

    final ServerLevel level;
    final String id;
    /** Far terrain is offered here (not a dimension with a ceiling). */
    final boolean offered;
    /** Tiles and the index go to disk (not for void dimensions, whose only content is what is loaded). */
    final boolean persist;
    @Nullable
    private final Estimator estimator;
    private final Supplier<FarTerrainSettings> settings;
    private final Workers workers;
    final RealIndex real = new RealIndex();
    private final RegionIndex regions;
    private final TileFiles files;
    private final Path indexFile;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final LinkedHashMap<Long, TileEntry> cache = new LinkedHashMap<>(256, 0.75f, true);
    private final Long2ObjectOpenHashMap<CompletableFuture<TileEntry>> serving = new Long2ObjectOpenHashMap<>();
    /** Tiles whose content changed (viewers holding them get the new version pushed). */
    final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();

    // ---- imports (guarded by imports) ----
    private final Long2ObjectOpenHashMap<CompletableFuture<Void>> imports = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet importQueue = new LongLinkedOpenHashSet();
    private int importsRunning;

    DimensionTerrain(ServerLevel level, SurfaceTable surfaces, Supplier<FarTerrainSettings> settings, Workers workers) {
        this.level = level;
        this.id = level.dimension().location().toString();
        this.offered = !level.dimensionType().hasCeiling();
        this.settings = settings;
        this.workers = workers;
        this.estimator = Estimator.of(level, surfaces);
        // only what exists in a void dimension is its content: nothing worth keeping across restarts beyond its chunks
        this.persist = estimator != null;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        this.regions = new RegionIndex(DimensionType.getStorageFolder(level.dimension(), root).resolve("region"));
        Path folder = root.resolve("blockland").resolve("farterrain").resolve(level.dimension().location().getNamespace())
                .resolve(level.dimension().location().getPath());
        this.files = new TileFiles(folder);
        this.indexFile = folder.resolve("real.bin");
        this.biomeCodec = ChunkColumns.biomeCodec(level);
        if (persist) real.load(indexFile);
    }

    // ---- serving ------------------------------------------------------------------------------------------------------

    /** The chunk is in its region file (worker threads: may read the file's table). */
    boolean regionHas(int cx, int cz) {
        return regions.exists(cx, cz);
    }

    /**
     * The tile brought up to date (real cells refreshed, missing cells imported or estimated), on a worker; concurrent
     * requests for one tile share the work. {@code order}: the requester's priority (smaller first).
     */
    CompletableFuture<TileEntry> serve(long key, double order) {
        CompletableFuture<TileEntry> future;
        synchronized (serving) {
            CompletableFuture<TileEntry> running = serving.get(key);
            if (running != null) return running;
            future = new CompletableFuture<>();
            serving.put(key, future);
        }
        CompletableFuture<TileEntry> f = future;
        workers.run(Workers.TILE, order, () -> step(key, order, 0, f));
        return future;
    }

    private void step(long key, double order, int round, CompletableFuture<TileEntry> future) {
        try {
            if (FarTile.level(key) >= NO_WAIT_LEVEL) {
                // a coarse tile answers at once with what is known and estimates; saved chunks it samples are read behind
                // it and the refreshed tile is pushed to whoever holds it
                TileEntry entry = refresh(key, null);
                finish(key, future, entry, null);
                LongOpenHashSet behind = settings.get().importSaved() ? importsFor(key) : null;
                if (behind != null && !behind.isEmpty()) {
                    importAll(behind).whenComplete((ignored, error) -> workers.run(Workers.TILE, order + 1e6, () -> refresh(key, null)));
                }
                return;
            }
            LongOpenHashSet wanted = round < 2 && settings.get().importSaved() ? new LongOpenHashSet() : null;
            TileEntry entry = refresh(key, wanted);
            if (wanted != null && !wanted.isEmpty()) {
                importAll(wanted).whenComplete((ignored, error) -> workers.run(Workers.TILE, order, () -> step(key, order, round + 1, future)));
                return;
            }
            finish(key, future, entry, null);
        } catch (Throwable t) {
            finish(key, future, null, t);
        }
    }

    private void finish(long key, CompletableFuture<TileEntry> future, @Nullable TileEntry entry, @Nullable Throwable error) {
        synchronized (serving) {
            serving.remove(key);
        }
        if (error != null) future.completeExceptionally(error);
        else future.complete(entry);
    }

    /**
     * Brings a tile up to date. With {@code imports} given, cells whose chunk is saved but not known yet are left for
     * later and their chunks collected (at most {@link #IMPORTS_PER_TILE}); without, they are estimated.
     */
    private TileEntry refresh(long key, @Nullable LongOpenHashSet imports) {
        TileEntry entry = entry(key);
        int level = FarTile.level(key);
        long startedAt = real.now();
        byte[] source;
        long builtAt;
        synchronized (entry) {
            entry.lastUse = System.currentTimeMillis();
            source = entry.sourceCopy();
            builtAt = entry.builtAt;
        }
        FarTile tile = entry.tile;
        IntList copies = new IntList();
        IntList estimates = new IntList();
        boolean importing = settings.get().importSaved();
        for (int j = 0; j < FarTile.SIZE; j++) {
            int z = tile.sampleZ(j);
            for (int i = 0; i < FarTile.SIZE; i++) {
                int x = tile.sampleX(i);
                int cell = FarTile.index(i, j);
                long chunk = Chunks.key(x >> 4, z >> 4);
                int state = real.state(chunk);
                byte src = source[cell];
                if (state == RealIndex.CAPTURED) {
                    if (level > 0 && (src != TileEntry.REAL || real.stamp(chunk) > builtAt)) copies.add(cell);
                    else if (level == 0 && src != TileEntry.REAL) wantImport(chunk, imports, estimates, cell, src);
                    continue;
                }
                if (importing && (state == RealIndex.STALE || state == RealIndex.UNKNOWN && regions.exists(x >> 4, z >> 4))) {
                    wantImport(chunk, imports, estimates, cell, src);
                    continue;
                }
                if (src == TileEntry.UNKNOWN) estimates.add(cell);
            }
        }
        boolean changed = false;
        if (!copies.isEmpty()) changed |= copyReal(entry, copies, imports);
        if (!estimates.isEmpty()) changed |= estimate(entry, estimates);
        synchronized (entry) {
            if (changed) entry.touched();
            entry.builtAt = Math.max(entry.builtAt, startedAt);
        }
        if (changed) this.changed.add(key);
        return entry;
    }

    private void wantImport(long chunk, @Nullable LongOpenHashSet imports, IntList estimates, int cell, byte src) {
        if (imports != null && (imports.contains(chunk) || imports.size() < IMPORTS_PER_TILE)) {
            imports.add(chunk);
        } else if (src == TileEntry.UNKNOWN) {
            estimates.add(cell);
        }
    }

    /** Copies the real cells listed from the level-0 tiles holding their columns. */
    private boolean copyReal(TileEntry target, IntList cells, @Nullable LongOpenHashSet imports) {
        FarTile tile = target.tile;
        // group by level-0 tile
        Long2ObjectOpenHashMap<IntList> groups = new Long2ObjectOpenHashMap<>();
        for (int n = 0; n < cells.size(); n++) {
            int cell = cells.get(n);
            int x = tile.sampleX(cell % FarTile.SIZE), z = tile.sampleZ(cell / FarTile.SIZE);
            long key0 = FarTile.key(0, FarTile.tileOf(x, 0), FarTile.tileOf(z, 0));
            IntList group = groups.get(key0);
            if (group == null) {
                group = new IntList();
                groups.put(key0, group);
            }
            group.add(cell);
        }
        boolean changed = false;
        FarTile scratch = new FarTile(tile.level, tile.tx, tile.tz);
        byte[] got = new byte[FarTile.CELLS];
        for (Long2ObjectMap.Entry<IntList> group : groups.long2ObjectEntrySet()) {
            TileEntry base = entry(group.getLongKey());
            IntList list = group.getValue();
            int ox = base.tile.originX(), oz = base.tile.originZ();
            synchronized (base) {
                for (int n = 0; n < list.size(); n++) {
                    int cell = list.get(n);
                    int from = FarTile.index(tile.sampleX(cell % FarTile.SIZE) - ox, tile.sampleZ(cell / FarTile.SIZE) - oz);
                    if (base.source[from] != TileEntry.REAL) {
                        got[cell] = 0;
                        continue;
                    }
                    scratch.copyCell(base.tile, from, cell);
                    got[cell] = 1;
                }
            }
            for (int n = 0; n < list.size(); n++) {
                int cell = list.get(n);
                if (got[cell] == 0) {
                    // the level-0 tile lost it (dropped unsaved): read the chunk again
                    long chunk = Chunks.key(tile.sampleX(cell % FarTile.SIZE) >> 4, tile.sampleZ(cell / FarTile.SIZE) >> 4);
                    real.forget(chunk);
                    if (imports != null && imports.size() < IMPORTS_PER_TILE) imports.add(chunk);
                }
            }
        }
        synchronized (target) {
            for (int n = 0; n < cells.size(); n++) {
                int cell = cells.get(n);
                if (got[cell] == 0) continue;
                if (scratch.isEmpty(cell)) {
                    if (!tile.isEmpty(cell) || target.source[cell] != TileEntry.REAL) changed = true;
                    tile.clear(cell);
                } else {
                    if (!sameCell(tile, scratch, cell) || target.source[cell] != TileEntry.REAL) changed = true;
                    tile.copyCell(scratch, cell, cell);
                }
                target.source[cell] = TileEntry.REAL;
            }
        }
        return changed;
    }

    private static boolean sameCell(FarTile a, FarTile b, int i) {
        if (a.isEmpty(i) != b.isEmpty(i)) return false;
        if (a.isEmpty(i)) return true;
        return a.height[i] == b.height[i] && a.topState(i) == b.topState(i) && a.underState(i) == b.underState(i)
                && a.waterDepth(i) == b.waterDepth(i) && a.biomeAt(i).equals(b.biomeAt(i));
    }

    /** Estimates the listed cells (outside the monitor: the generator's columns take a while) and writes them in. */
    private boolean estimate(TileEntry target, IntList cells) {
        FarTile tile = target.tile;
        int n = cells.size();
        int[] height = new int[n], top = new int[n], under = new int[n], water = new int[n];
        String[] biome = new String[n];
        boolean allowed = estimator != null && settings.get().estimate();
        Estimator.Sample sample = new Estimator.Sample();
        for (int k = 0; k < n; k++) {
            if (!allowed) {
                height[k] = FarTile.EMPTY;
                continue;
            }
            int cell = cells.get(k);
            try {
                estimator.sample(tile.sampleX(cell % FarTile.SIZE), tile.sampleZ(cell / FarTile.SIZE), FarTile.cell(tile.level), sample);
            } catch (RuntimeException e) {
                sample.height = FarTile.EMPTY;
            }
            height[k] = sample.height;
            top[k] = sample.top;
            under[k] = sample.under;
            water[k] = sample.water;
            biome[k] = sample.biome;
        }
        boolean changed = false;
        synchronized (target) {
            for (int k = 0; k < n; k++) {
                int cell = cells.get(k);
                byte src = target.source[cell];
                if (src == TileEntry.REAL) continue; // a real column arrived meanwhile
                if (height[k] == FarTile.EMPTY) {
                    tile.clear(cell);
                    target.source[cell] = allowed ? TileEntry.ESTIMATE : TileEntry.NONE;
                } else {
                    tile.set(cell, height[k], top[k], under[k], biome[k], water[k]);
                    target.source[cell] = TileEntry.ESTIMATE;
                }
                changed = true;
            }
        }
        return changed;
    }

    // ---- captures and imports -----------------------------------------------------------------------------------------

    /** Writes a chunk's columns into its level-0 tile and records it as known; worker thread. */
    void merge(ChunkColumns columns) {
        int x0 = columns.cx << 4, z0 = columns.cz << 4;
        long key0 = FarTile.key(0, FarTile.tileOf(x0, 0), FarTile.tileOf(z0, 0));
        TileEntry entry = entry(key0);
        FarTile tile = entry.tile;
        int ox = x0 - tile.originX(), oz = z0 - tile.originZ();
        boolean changed = false;
        synchronized (entry) {
            entry.lastUse = System.currentTimeMillis();
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int c = lx + lz * 16;
                    int cell = FarTile.index(ox + lx, oz + lz);
                    boolean wasReal = entry.source[cell] == TileEntry.REAL;
                    if (columns.height[c] == FarTile.EMPTY) {
                        if (!tile.isEmpty(cell) || !wasReal) changed = true;
                        tile.clear(cell);
                    } else {
                        String biome = columns.biomeAt(c);
                        if (!wasReal || tile.isEmpty(cell) || tile.height[cell] != columns.height[c] || tile.topState(cell) != columns.top[c]
                                || tile.underState(cell) != columns.under[c] || tile.waterDepth(cell) != (columns.water[c] & 0xFF)
                                || !tile.biomeAt(cell).equals(biome)) {
                            changed = true;
                            tile.set(cell, columns.height[c], columns.top[c], columns.under[c], biome, columns.water[c] & 0xFF);
                        }
                    }
                    entry.source[cell] = TileEntry.REAL;
                }
            }
            if (changed) entry.touched();
        }
        long chunk = columns.chunk();
        if (!changed && real.state(chunk) == RealIndex.CAPTURED) return;
        // a new stamp: coarser tiles sampling this chunk take its columns at their next refresh
        real.captured(chunk);
        notifyChunk(columns.cx, columns.cz);
    }

    /** Every tile with a cell sampled in this chunk changed (coarse levels: only those whose sample lies in it). */
    private void notifyChunk(int cx, int cz) {
        int a = cx << 4, b = cz << 4;
        for (int level = 0; level <= FarTile.MAX_LEVEL; level++) {
            if (Chunks.sampled(level, cx, cz)) changed.add(FarTile.key(level, FarTile.tileOf(a, level), FarTile.tileOf(b, level)));
        }
    }

    /** Reads the saved chunks listed (through the server's chunk storage, a few at a time) and merges them. */
    /** The saved chunks a tile samples that are not captured yet (at most {@link #IMPORTS_PER_TILE}). */
    private LongOpenHashSet importsFor(long key) {
        FarTile tile = entry(key).tile;
        LongOpenHashSet out = new LongOpenHashSet();
        for (int j = 0; j < FarTile.SIZE && out.size() < IMPORTS_PER_TILE; j++) {
            int z = tile.sampleZ(j);
            for (int i = 0; i < FarTile.SIZE && out.size() < IMPORTS_PER_TILE; i++) {
                int x = tile.sampleX(i);
                long chunk = Chunks.key(x >> 4, z >> 4);
                int state = real.state(chunk);
                if (state == RealIndex.STALE || state == RealIndex.UNKNOWN && regions.exists(x >> 4, z >> 4)) out.add(chunk);
            }
        }
        return out;
    }

    private CompletableFuture<Void> importAll(LongOpenHashSet chunks) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(chunks.size());
        synchronized (imports) {
            for (long chunk : chunks) {
                CompletableFuture<Void> f = imports.get(chunk);
                if (f == null) {
                    f = new CompletableFuture<>();
                    imports.put(chunk, f);
                    importQueue.add(chunk);
                }
                futures.add(f);
            }
        }
        pumpImports();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private void pumpImports() {
        while (true) {
            long chunk;
            synchronized (imports) {
                if (importsRunning >= IMPORTS_IN_FLIGHT || importQueue.isEmpty()) return;
                chunk = importQueue.removeFirstLong();
                importsRunning++;
            }
            ChunkPos pos = new ChunkPos(Chunks.x(chunk), Chunks.z(chunk));
            CompletableFuture<Optional<CompoundTag>> read;
            try {
                read = level.getChunkSource().chunkMap.read(pos);
            } catch (Throwable t) {
                read = CompletableFuture.failedFuture(t);
            }
            read.whenComplete((tag, error) -> workers.run(Workers.MERGE, 0, () -> imported(chunk, error == null ? tag : null)));
        }
    }

    private void imported(long chunk, @Nullable Optional<CompoundTag> tag) {
        try {
            ChunkColumns columns = null;
            if (tag != null && tag.isPresent()) {
                ChunkColumns.Refusal[] refusal = {ChunkColumns.Refusal.NONE};
                // a chunk of an older game version is brought up to date first (it is real terrain all the same)
                columns = ChunkColumns.parse(level, biomeCodec, SavedChunks.current(level, tag.get()), refusal);
            }
            // a capture may have overtaken the read: the live data wins
            if (real.state(chunk) != RealIndex.CAPTURED) {
                if (columns != null) merge(columns);
                else real.mark(chunk, RealIndex.NOT_FULL);
            }
        } catch (Throwable t) {
            BlockLand.LOGGER.debug("[BlockLand] Far terrain could not import chunk {} of {}", new ChunkPos(chunk), id, t);
            real.mark(chunk, RealIndex.NOT_FULL);
        } finally {
            CompletableFuture<Void> f;
            synchronized (imports) {
                importsRunning--;
                f = imports.remove(chunk);
            }
            if (f != null) f.complete(null);
            pumpImports();
        }
    }

    // ---- the cache ----------------------------------------------------------------------------------------------------

    /** The tile's entry: in memory, else from disk, else a new one (level 0: its captured chunks' data is gone then). */
    TileEntry entry(long key) {
        synchronized (cache) {
            TileEntry entry = cache.get(key);
            if (entry != null) return entry;
        }
        TileEntry loaded = persist ? files.read(key) : null;
        if (loaded != null) real.advance(loaded.builtAt);
        boolean fresh = loaded == null;
        if (fresh) loaded = new TileEntry(key);
        loaded.lastUse = System.currentTimeMillis();
        TileEntry entry;
        synchronized (cache) {
            entry = cache.putIfAbsent(key, loaded);
            if (entry == null) {
                entry = loaded;
                evict();
            } else {
                return entry;
            }
        }
        if (fresh && FarTile.level(key) == 0) {
            // whatever was captured into this tile before is not here: those chunks are read again when needed
            int cx0 = FarTile.tx(key) * 4, cz0 = FarTile.tz(key) * 4;
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) real.forget(Chunks.key(cx0 + dx, cz0 + dz));
            }
        }
        return entry;
    }

    /** Drops least recently used entries over the limit: never one being worked on, never one not yet on disk. */
    private void evict() {
        int over = cache.size() - CACHE;
        if (over <= 0) return;
        long now = System.currentTimeMillis();
        Iterator<TileEntry> it = cache.values().iterator();
        while (over > 0 && it.hasNext()) {
            TileEntry e = it.next();
            synchronized (e) {
                if (now - e.lastUse < KEEP_MS || persist && e.dirty()) continue;
            }
            it.remove();
            over--;
        }
    }

    /** Writes up to {@code max} changed tiles on workers (or right here when {@code now}); returns how many were due. */
    int flush(int max, boolean now) {
        if (!persist) return 0;
        List<TileEntry> due = new ArrayList<>();
        synchronized (cache) {
            for (TileEntry e : cache.values()) {
                if (due.size() >= max) break;
                synchronized (e) {
                    if (e.dirty()) due.add(e);
                }
            }
        }
        int band = crowded() ? Workers.MERGE : Workers.WRITE;
        for (TileEntry e : due) {
            if (now) write(e);
            else workers.run(band, 0, () -> write(e));
        }
        return due.size();
    }

    /** More tiles in memory than the cache holds (unwritten ones cannot leave it). */
    boolean crowded() {
        synchronized (cache) {
            return cache.size() > CACHE;
        }
    }

    private void write(TileEntry e) {
        long changes;
        byte[] bytes, source;
        long builtAt;
        FarTile copy = null;
        synchronized (e) {
            if (!e.dirty()) return;
            changes = e.changes;
            bytes = e.encoded;
            if (bytes == null) copy = e.copy();
            source = e.sourceCopy();
            builtAt = e.builtAt;
        }
        if (copy != null) bytes = copy.encode();
        try {
            files.write(e.key, bytes, source, builtAt);
            synchronized (e) {
                e.written = Math.max(e.written, changes);
                if (e.changes == changes && e.encoded == null) e.encoded = bytes;
            }
        } catch (IOException ex) {
            BlockLand.LOGGER.warn("[BlockLand] Could not write far terrain tile {} of {}", FarTile.level(e.key) + "/" + FarTile.tx(e.key)
                    + "/" + FarTile.tz(e.key), id, ex);
        }
    }

    void saveIndex() {
        if (persist) real.save(indexFile);
    }

    /** Encoded bytes and version of an entry as it is now (encoded outside the monitor when they were not cached). */
    static Encoded encoded(TileEntry e) {
        long changes;
        byte[] bytes;
        long version;
        boolean empty;
        FarTile copy = null;
        synchronized (e) {
            changes = e.changes;
            bytes = e.encoded;
            version = e.tile.version != 0 ? e.tile.version : e.tile.computeVersion();
            empty = e.tile.isEmpty();
            if (bytes == null && !empty) copy = e.copy();
        }
        if (copy != null) {
            bytes = copy.encode();
            synchronized (e) {
                if (e.changes == changes && e.encoded == null) e.encoded = bytes;
            }
        }
        return new Encoded(version, empty, bytes);
    }

    record Encoded(long version, boolean empty, @Nullable byte[] bytes) {
    }

    int cached() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** A growable list of ints (cell indices). */
    static final class IntList {
        private int[] data = new int[64];
        private int size;

        void add(int v) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, size * 2);
            data[size++] = v;
        }

        int get(int i) {
            return data[i];
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }
}
