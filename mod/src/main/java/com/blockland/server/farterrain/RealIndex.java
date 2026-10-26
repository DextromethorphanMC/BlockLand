package com.blockland.server.farterrain;

import com.blockland.BlockLand;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Which chunks of a dimension far terrain knows for real (rendering spec §22) and since when: a chunk captured live or
 * imported from its region file gets a fresh stamp from a counter that only grows, so a tile built at stamp s knows its
 * cells need refreshing exactly where a chunk it samples has a later stamp. A chunk whose saved data is newer than what
 * was captured (it changed while it could not be captured) is {@link #STALE}: read again from disk when needed; one
 * that exists but is not fully generated is {@link #NOT_FULL} (estimated meanwhile). Thread-safe.
 */
final class RealIndex {
    static final int UNKNOWN = 0, CAPTURED = 1, STALE = 2, NOT_FULL = 3;
    private static final int FORMAT = 1;

    private final Long2LongOpenHashMap chunks = new Long2LongOpenHashMap();
    private long stamp = 1;
    private boolean changed;

    synchronized int state(long chunk) {
        return (int) (chunks.get(chunk) & 3);
    }

    /** The chunk's capture stamp (0 when it was never captured). */
    synchronized long stamp(long chunk) {
        long v = chunks.get(chunk);
        return (v & 3) == CAPTURED ? v >>> 2 : 0;
    }

    /** The current stamp: everything captured so far has a stamp at most this. */
    synchronized long now() {
        return stamp;
    }

    /**
     * Never hands out a stamp at or below {@code at} again (a tile on disk was built at {@code at}: after a crash the
     * saved index may be older than the tiles, and a capture must still count as newer than them).
     */
    synchronized void advance(long at) {
        if (at > stamp) {
            stamp = at;
            changed = true;
        }
    }

    /** Records a capture; returns its stamp. */
    synchronized long captured(long chunk) {
        long s = ++stamp;
        chunks.put(chunk, s << 2 | CAPTURED);
        changed = true;
        return s;
    }

    synchronized void mark(long chunk, int state) {
        long v = chunks.get(chunk);
        if ((v & 3) == state) return;
        chunks.put(chunk, (v & ~3L) | state);
        changed = true;
    }

    /** A captured chunk whose data was lost (its level-0 tile was dropped unsaved): read it again when needed. */
    synchronized void forget(long chunk) {
        if ((chunks.get(chunk) & 3) == CAPTURED) mark(chunk, STALE);
    }

    synchronized int size() {
        return chunks.size();
    }

    // ---- persistence ------------------------------------------------------------------------------------------------

    synchronized void load(Path file) {
        if (!Files.isRegularFile(file)) return;
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
            if (in.readInt() != FORMAT) return;
            stamp = Math.max(stamp, in.readLong());
            int n = in.readInt();
            for (int i = 0; i < n; i++) chunks.put(in.readLong(), in.readLong());
        } catch (IOException | RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Far terrain index {} unreadable; chunks are captured again as they load", file, e);
            chunks.clear();
        }
    }

    void save(Path file) {
        long[] keys, values;
        long at;
        synchronized (this) {
            if (!changed) return;
            changed = false;
            at = stamp;
            keys = new long[chunks.size()];
            values = new long[chunks.size()];
            int i = 0;
            for (Long2LongMap.Entry e : chunks.long2LongEntrySet()) {
                keys[i] = e.getLongKey();
                values[i++] = e.getLongValue();
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(raw))) {
                out.writeInt(FORMAT);
                out.writeLong(at);
                out.writeInt(keys.length);
                for (int i = 0; i < keys.length; i++) {
                    out.writeLong(keys[i]);
                    out.writeLong(values[i]);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            synchronized (this) {
                changed = true;
            }
            BlockLand.LOGGER.warn("[BlockLand] Could not save the far terrain index {}", file, e);
        }
    }
}
