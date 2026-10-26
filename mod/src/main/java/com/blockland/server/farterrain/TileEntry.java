package com.blockland.server.farterrain;

import com.blockland.farterrain.FarTile;

import java.util.Arrays;

/**
 * A tile as the server keeps it (rendering spec §22): the {@link FarTile} viewers get, where each of its cells came from
 * ({@link #source}), the real index's stamp it was last brought up to date with, and its encoded bytes while unchanged.
 * Every access holds the entry's monitor; long work (estimates, encoding) happens on copies outside it.
 */
final class TileEntry {
    /** Cell sources: not filled yet, estimated, captured or imported, known to hold nothing (void, no estimator). */
    static final byte UNKNOWN = 0, ESTIMATE = 1, REAL = 2, NONE = 3;

    final long key;
    final FarTile tile;
    final byte[] source = new byte[FarTile.CELLS];
    /** {@link RealIndex#now} when the real cells were last refreshed. */
    long builtAt;
    /** Bumped on every change (the disk writer compares it). */
    long changes;
    /** {@link #changes} as last written to disk ({@code -1}: never). */
    long written = -1;
    byte[] encoded;
    long lastUse;

    TileEntry(long key) {
        this.key = key;
        this.tile = new FarTile(FarTile.level(key), FarTile.tx(key), FarTile.tz(key));
    }

    TileEntry(FarTile tile, byte[] source, long builtAt) {
        this.key = tile.key();
        this.tile = tile;
        System.arraycopy(source, 0, this.source, 0, Math.min(source.length, FarTile.CELLS));
        this.builtAt = builtAt;
        this.encoded = null;
    }

    boolean dirty() {
        return written != changes;
    }

    /** Marks the content changed: a new version, no cached bytes, to be written. */
    void touched() {
        tile.computeVersion();
        encoded = null;
        changes++;
    }

    /** A copy of the tile (palettes included) to encode or clip outside the monitor. */
    FarTile copy() {
        FarTile copy = new FarTile(tile.level, tile.tx, tile.tz);
        for (int i = 0; i < FarTile.CELLS; i++) {
            if (!tile.isEmpty(i)) copy.copyCell(tile, i, i);
        }
        copy.version = tile.version;
        return copy;
    }

    byte[] sourceCopy() {
        return Arrays.copyOf(source, source.length);
    }

    /** Whether every cell has been filled (from any source). */
    boolean complete() {
        for (byte s : source) if (s == UNKNOWN) return false;
        return true;
    }
}
