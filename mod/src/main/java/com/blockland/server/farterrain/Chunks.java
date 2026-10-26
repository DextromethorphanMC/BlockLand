package com.blockland.server.farterrain;

import com.blockland.farterrain.FarTile;

/** Chunk positions packed into longs (x low, z high) and which far tiles sample a chunk (rendering spec §22). */
final class Chunks {
    private Chunks() {
    }

    static long key(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    static int x(long key) {
        return (int) key;
    }

    static int z(long key) {
        return (int) (key >>> 32);
    }

    /**
     * Whether a cell of the given level samples a column inside chunk (cx, cz): always for cells of 16 blocks or less; for
     * coarser cells only where the chunk holds a cell centre (centres sit at {@code sampleOffset} modulo the cell size, a
     * multiple of 16 from 32 blocks on, so it is the chunk's corner column or none).
     */
    static boolean sampled(int level, int cx, int cz) {
        int cell = FarTile.cell(level);
        if (cell <= 16) return true;
        int offset = FarTile.sampleOffset(level);
        return Math.floorMod((cx << 4) - offset, cell) == 0 && Math.floorMod((cz << 4) - offset, cell) == 0;
    }
}
