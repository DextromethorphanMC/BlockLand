package com.blockland.server.farterrain;

import com.blockland.farterrain.FarColumn;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;

/**
 * The plain rules of the real far chunk units (rendering spec §23), kept apart from Minecraft's blocks so they can be
 * checked offline: how eight cubes of one scale become one of the next ({@link #pick}), which chunks of a unit exist
 * ({@link #union}), how far a unit lies from a player and whether it touches their view limit, and the stamps that say
 * whether a unit kept on disk was built from the chunks saved now ({@link #stamp}).
 */
final class UnitMath {
    /** What a cube holds for {@link #pick}: nothing (air, flowing fluid), a block (fluid sources other than water too), water. */
    static final byte AIR = 0, BLOCK = 1, WATER = 2;
    /** {@link #pick}: no member stands for the cube, it is air; or water's own default state (a source). */
    static final int PICK_AIR = -1, PICK_WATER = -2;

    private UnitMath() {
    }

    // ---- downsampling -----------------------------------------------------------------------------------------------

    /**
     * The cube one scale coarser that eight cubes make (member {@code i}: x = i & 1, z = i >> 1 & 1, y = i >> 2, y 0 below).
     * Fewer than half of them hold something: air. Water most of what is there: water. Otherwise the most common state
     * among the members seen from above (something with air over it in the group, or in its top layer), a tie going to
     * the one that reaches higher; the index of that member (the highest one of its state).
     */
    static int pick(Object[] members, byte[] kinds) {
        int solid = 0, water = 0;
        for (int i = 0; i < 8; i++) {
            if (kinds[i] == AIR) continue;
            solid++;
            if (kinds[i] == WATER) water++;
        }
        if (solid < 4) return PICK_AIR;
        if (water * 2 > solid) return PICK_WATER;
        int best = PICK_AIR, bestCount = 0, bestLayer = -1;
        // the top layer first, so of two states seen as often the higher one is met first and kept
        for (int i = 7; i >= 0; i--) {
            if (!exposed(kinds, i)) continue;
            int count = 0;
            for (int j = 0; j < 8; j++) {
                if (exposed(kinds, j) && members[j].equals(members[i])) count++;
            }
            int layer = i >> 2;
            if (count > bestCount || count == bestCount && layer > bestLayer) {
                best = i;
                bestCount = count;
                bestLayer = layer;
            }
        }
        return best;
    }

    private static boolean exposed(byte[] kinds, int i) {
        return kinds[i] != AIR && (i >= 4 || kinds[i + 4] == AIR);
    }

    // ---- presence ---------------------------------------------------------------------------------------------------

    /**
     * The presence mask of a unit of {@code scale} (≥ 1) from its four children (index dx + 2 dz): a child that is not
     * there ({@code exists} false) has none of its chunks, one there with a null mask all of them. Null when every chunk
     * of the unit exists.
     */
    @Nullable
    static long[] union(int scale, long[][] masks, boolean[] exists) {
        int side = 1 << scale, half = side >> 1, childScale = scale - 1;
        long[] out = new long[Math.max(1, side * side + 63 >> 6)];
        boolean all = true;
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                int c = dx / half + 2 * (dz / half);
                int bit = dx % half + (dz % half << childScale);
                boolean on = exists[c] && (masks[c] == null || (masks[c][bit >> 6] >>> (bit & 63) & 1) != 0);
                int index = dx + (dz << scale);
                if (on) out[index >> 6] |= 1L << (index & 63);
                else all = false;
            }
        }
        return all ? null : out;
    }

    // ---- keys, distance, limits -------------------------------------------------------------------------------------

    /** A key a client may ask for: a known scale, nothing in the spare bits, its chunks well inside the world. */
    static boolean valid(long key) {
        if (key >>> 60 != 0 || FarColumn.scale(key) > FarColumn.MAX_SCALE) return false;
        long x0 = chunkX0(key), z0 = chunkZ0(key);
        return Math.abs(x0) < 4_000_000 && Math.abs(z0) < 4_000_000;
    }

    /** The unit's corner in chunks (wide: a bad key never overflows). */
    static long chunkX0(long key) {
        return (long) FarColumn.x(key) << FarColumn.scale(key);
    }

    static long chunkZ0(long key) {
        return (long) FarColumn.z(key) << FarColumn.scale(key);
    }

    /** Distance in chunks (rounded down, Euclidean) from the player's chunk to the unit's nearest chunk (0 inside it). */
    static int distance(int pcx, int pcz, long key) {
        long side = 1L << FarColumn.scale(key);
        long x0 = chunkX0(key), z0 = chunkZ0(key);
        long dx = Math.max(0, Math.max(x0 - pcx, pcx - (x0 + side - 1)));
        long dz = Math.max(0, Math.max(z0 - pcz, pcz - (z0 + side - 1)));
        return (int) Math.min(Integer.MAX_VALUE, Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    /** Distance in chunks (rounded down) from the player's chunk to the unit's farthest chunk. */
    static int farthest(int pcx, int pcz, long key) {
        long side = 1L << FarColumn.scale(key);
        long x0 = chunkX0(key), z0 = chunkZ0(key);
        long dx = Math.max(Math.abs(x0 - pcx), Math.abs(x0 + side - 1 - pcx));
        long dz = Math.max(Math.abs(z0 - pcz), Math.abs(z0 + side - 1 - pcz));
        return (int) Math.min(Integer.MAX_VALUE, Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    /** The unit's blocks touch the view limit (none = unlimited). */
    static boolean touches(@Nullable BoundingBox limit, long key) {
        if (limit == null) return true;
        long side = 16L << FarColumn.scale(key);
        long x0 = chunkX0(key) << 4, z0 = chunkZ0(key) << 4;
        return x0 <= limit.maxX() && x0 + side - 1 >= limit.minX() && z0 <= limit.maxZ() && z0 + side - 1 >= limit.minZ();
    }

    /** Every chunk of the unit touches the view limit (nothing of it needs to be cut away). */
    static boolean within(@Nullable BoundingBox limit, long key) {
        if (limit == null) return true;
        long side = 16L << FarColumn.scale(key);
        long x0 = chunkX0(key) << 4, z0 = chunkZ0(key) << 4;
        // the outermost chunks only have to touch it, as a chunk of scale 0 does
        return x0 + 15 >= limit.minX() && x0 + side - 16 <= limit.maxX() && z0 + 15 >= limit.minZ() && z0 + side - 16 <= limit.maxZ();
    }

    /** The chunk touches the view limit. */
    static boolean chunkTouches(BoundingBox limit, long chunkX, long chunkZ) {
        long x0 = chunkX << 4, z0 = chunkZ << 4;
        return x0 <= limit.maxX() && x0 + 15 >= limit.minX() && z0 <= limit.maxZ() && z0 + 15 >= limit.minZ();
    }

    /** Workers' and queues' order of a unit for a player: nearest first, finer before coarser at the same distance. */
    static int rank(int pcx, int pcz, long key) {
        return (int) Math.min(Integer.MAX_VALUE / 8 - 1, distance(pcx, pcz, key)) * 8 + FarColumn.scale(key);
    }

    /** The chunk's place on a Z-order curve: saved chunks are read in it, so the units they make finish one after another. */
    static long morton(long chunkX, long chunkZ) {
        return spread(chunkX + (1L << 22)) | spread(chunkZ + (1L << 22)) << 1;
    }

    private static long spread(long v) {
        v &= 0x7FFFFFL;
        long out = 0;
        for (int b = 0; b < 23; b++) out |= (v >>> b & 1) << 2 * b;
        return out;
    }

    /** A question about one chunk. */
    interface ChunkTest {
        boolean test(int chunkX, int chunkZ);
    }

    /** Whether any chunk of the unit passes (stops at the first). */
    static boolean anyChunk(long key, ChunkTest test) {
        int side = 1 << FarColumn.scale(key);
        int x0 = FarColumn.chunkX(key), z0 = FarColumn.chunkZ(key);
        for (int dz = 0; dz < side; dz++) {
            for (int dx = 0; dx < side; dx++) {
                if (test.test(x0 + dx, z0 + dz)) return true;
            }
        }
        return false;
    }

    // ---- stamps -----------------------------------------------------------------------------------------------------

    /** The stamp of one chunk as it is saved now (0: not saved). */
    interface ChunkStamps {
        long of(int chunkX, int chunkZ);
    }

    /**
     * What a unit was built from: a chunk's own stamp (its region file's timestamp), for coarser units a hash of their
     * four children's stamps. A unit kept on disk is good while the stamps of its children are what they were then.
     */
    static long stamp(long key, ChunkStamps chunks) {
        int scale = FarColumn.scale(key);
        if (scale == 0) return chunks.of(FarColumn.x(key), FarColumn.z(key));
        return combine(childStamps(key, chunks));
    }

    static long[] childStamps(long key, ChunkStamps chunks) {
        long[] out = new long[4];
        for (int i = 0; i < 4; i++) out[i] = stamp(FarColumn.child(key, i), chunks);
        return out;
    }

    static long combine(long[] stamps) {
        long h = 0x243F6A8885A308D3L;
        for (long s : stamps) h = mix(h ^ s);
        return h;
    }

    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }
}
