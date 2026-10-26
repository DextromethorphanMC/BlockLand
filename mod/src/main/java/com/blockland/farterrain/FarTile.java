package com.blockland.farterrain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One tile of the far terrain (rendering spec §22), shared by the server that fills it, the network and the client that
 * draws it: 64 × 64 cells of {@code 2^level} blocks, each a point sample of the real (or estimated) terrain at its centre
 * column — the height of its top face, the top block, the block under it (or, under water, the floor), the biome and the
 * water's depth. Block states travel as {@code Block.BLOCK_STATE_REGISTRY} ids and biomes as resource ids, each through a
 * palette of at most 256 entries. Not thread-safe; a tile handed to another thread is not changed any more.
 */
public final class FarTile {
    /** Cells per tile side. */
    public static final int SIZE = 64;
    public static final int CELLS = SIZE * SIZE;
    /** Coarsest level: cells of 512 blocks, tiles of 32 768. */
    public static final int MAX_LEVEL = 9;
    /** A cell without terrain (void, unknown, outside the allowed region). */
    public static final short EMPTY = Short.MIN_VALUE;
    /** Most entries a palette holds (indices are bytes). */
    public static final int MAX_PALETTE = 256;
    private static final int FORMAT = 1;

    public final int level, tx, tz;
    /** Per cell: y of the top face (the top block's y + 1), or {@link #EMPTY}. */
    public final short[] height = new short[CELLS];
    /** Per cell: palette indices of the top block and the block under it (the floor under water), unsigned. */
    public final byte[] top = new byte[CELLS], under = new byte[CELLS];
    /** Per cell: biome palette index, unsigned. */
    public final byte[] biome = new byte[CELLS];
    /** Per cell: 0 = no water, else the water's depth to the floor in blocks (1..255), unsigned. */
    public final byte[] water = new byte[CELLS];
    private int[] states = new int[8];
    private int stateCount;
    private String[] biomes = new String[4];
    private int biomeCount;
    /** Content hash ({@link #computeVersion}); 0 = not computed. */
    public long version;

    public FarTile(int level, int tx, int tz) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("level " + level);
        this.level = level;
        this.tx = tx;
        this.tz = tz;
        Arrays.fill(height, EMPTY);
    }

    // ---- keys and geometry ------------------------------------------------------------------------------------------

    /** Level in the top 6 bits, tx and tz in 29 bits each (two's complement). */
    public static long key(int level, int tx, int tz) {
        return (long) level << 58 | ((long) tx & 0x1FFFFFFFL) << 29 | (long) tz & 0x1FFFFFFFL;
    }

    public static int level(long key) {
        return (int) (key >>> 58);
    }

    public static int tx(long key) {
        return (int) (key << 6 >> 35);
    }

    public static int tz(long key) {
        return (int) (key << 35 >> 35);
    }

    public long key() {
        return key(level, tx, tz);
    }

    /** Blocks per cell. */
    public static int cell(int level) {
        return 1 << level;
    }

    /** Blocks per tile side. */
    public static int span(int level) {
        return SIZE << level;
    }

    /** Offset of a cell's sampled column from its corner (its centre; the block itself at level 0). */
    public static int sampleOffset(int level) {
        return level == 0 ? 0 : 1 << (level - 1);
    }

    /** World x of the tile's west edge. */
    public int originX() {
        return tx * span(level);
    }

    /** World z of the tile's north edge. */
    public int originZ() {
        return tz * span(level);
    }

    /** World x of the column cell i is sampled at. */
    public int sampleX(int i) {
        return originX() + (i << level) + sampleOffset(level);
    }

    /** World z of the column cell j is sampled at. */
    public int sampleZ(int j) {
        return originZ() + (j << level) + sampleOffset(level);
    }

    /** The tile of a level holding a world column. */
    public static int tileOf(int block, int level) {
        return Math.floorDiv(block, span(level));
    }

    public static int index(int i, int j) {
        return i + j * SIZE;
    }

    // ---- palettes ---------------------------------------------------------------------------------------------------

    /** The palette index of a block state id, added if new; the last entry when the palette is full. */
    public int stateIndex(int stateId) {
        for (int i = 0; i < stateCount; i++) if (states[i] == stateId) return i;
        if (stateCount == MAX_PALETTE) return MAX_PALETTE - 1;
        if (stateCount == states.length) states = Arrays.copyOf(states, states.length * 2);
        states[stateCount] = stateId;
        return stateCount++;
    }

    /** The palette index of a biome id, added if new; the last entry when the palette is full. */
    public int biomeIndex(String biomeId) {
        for (int i = 0; i < biomeCount; i++) if (biomes[i].equals(biomeId)) return i;
        if (biomeCount == MAX_PALETTE) return MAX_PALETTE - 1;
        if (biomeCount == biomes.length) biomes = Arrays.copyOf(biomes, biomes.length * 2);
        biomes[biomeCount] = biomeId;
        return biomeCount++;
    }

    public int stateCount() {
        return stateCount;
    }

    public int biomeCount() {
        return biomeCount;
    }

    /** Block state id of a palette entry. */
    public int state(int index) {
        return states[index];
    }

    public String biome(int index) {
        return biomes[index];
    }

    // ---- cells ------------------------------------------------------------------------------------------------------

    /** Sets a cell from ids (palettes grow as needed); {@code waterDepth} 0 = no water. */
    public void set(int index, int topHeight, int topState, int underState, String biomeId, int waterDepth) {
        height[index] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, topHeight));
        top[index] = (byte) stateIndex(topState);
        under[index] = (byte) stateIndex(underState);
        biome[index] = (byte) biomeIndex(biomeId);
        water[index] = (byte) Math.max(0, Math.min(255, waterDepth));
    }

    public void clear(int index) {
        height[index] = EMPTY;
        top[index] = under[index] = biome[index] = water[index] = 0;
    }

    public boolean isEmpty(int index) {
        return height[index] == EMPTY;
    }

    /** No cell holds terrain. */
    public boolean isEmpty() {
        for (short h : height) if (h != EMPTY) return false;
        return true;
    }

    public int topState(int index) {
        return states[top[index] & 0xFF];
    }

    public int underState(int index) {
        return states[under[index] & 0xFF];
    }

    public String biomeAt(int index) {
        return biomes[biome[index] & 0xFF];
    }

    public int waterDepth(int index) {
        return water[index] & 0xFF;
    }

    /** Copies cell {@code from} of {@code source} (palettes translated) into cell {@code to} of this tile. */
    public void copyCell(FarTile source, int from, int to) {
        if (source.isEmpty(from)) {
            clear(to);
            return;
        }
        set(to, source.height[from], source.topState(from), source.underState(from), source.biomeAt(from), source.waterDepth(from));
    }

    // ---- version and codec ------------------------------------------------------------------------------------------

    /** A 64-bit hash of everything a viewer sees (cells resolved through the palettes); also stored in {@link #version}. */
    public long computeVersion() {
        long h = 0x9E3779B97F4A7C15L ^ key();
        for (int i = 0; i < CELLS; i++) {
            long v;
            if (height[i] == EMPTY) {
                v = 0x5555;
            } else {
                v = (long) height[i] & 0xFFFF;
                v = v * 31 + topState(i);
                v = v * 31 + underState(i);
                v = v * 31 + biomeAt(i).hashCode();
                v = v * 31 + (water[i] & 0xFF);
            }
            h = mix(h ^ v + i);
        }
        if (h == 0) h = 1;
        version = h;
        return h;
    }

    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /**
     * The tile as bytes: format, level, tx, tz, version, the palettes (state ids; biome ids in UTF-8), then per row the
     * heights as deltas along it and the four byte planes — deflated.
     */
    public byte[] encode() {
        if (version == 0) computeVersion();
        int biomeBytes = 0;
        byte[][] names = new byte[biomeCount][];
        for (int i = 0; i < biomeCount; i++) {
            names[i] = biomes[i].getBytes(StandardCharsets.UTF_8);
            biomeBytes += 2 + names[i].length;
        }
        ByteBuffer raw = ByteBuffer.allocate(4 + 4 * 3 + 8 + 4 + stateCount * 4 + 4 + biomeBytes + CELLS * 2 + CELLS * 4);
        raw.putInt(FORMAT).putInt(level).putInt(tx).putInt(tz).putLong(version);
        raw.putInt(stateCount);
        for (int i = 0; i < stateCount; i++) raw.putInt(states[i]);
        raw.putInt(biomeCount);
        for (byte[] name : names) {
            raw.putShort((short) name.length);
            raw.put(name);
        }
        for (int j = 0; j < SIZE; j++) {
            short previous = 0;
            for (int i = 0; i < SIZE; i++) {
                short h = height[index(i, j)];
                raw.putShort((short) (h - previous));
                previous = h;
            }
        }
        raw.put(top).put(under).put(biome).put(water);
        Deflater deflater = new Deflater(6);
        try {
            deflater.setInput(raw.array(), 0, raw.position());
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.position() / 4);
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** The tile {@link #encode} wrote; throws {@link IllegalArgumentException} on anything malformed. */
    public static FarTile decode(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            // the largest a tile can be: header, full palettes with long biome names, the cells
            byte[] raw = new byte[64 * 1024 + MAX_PALETTE * 4 + MAX_PALETTE * 258 + CELLS * 6];
            int length = 0;
            while (!inflater.finished()) {
                if (length == raw.length) throw new IllegalArgumentException("far tile too large");
                int n = inflater.inflate(raw, length, raw.length - length);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalArgumentException("truncated far tile");
                length += n;
            }
            ByteBuffer in = ByteBuffer.wrap(raw, 0, length);
            if (in.getInt() != FORMAT) throw new IllegalArgumentException("unknown far tile format");
            int level = in.getInt(), tx = in.getInt(), tz = in.getInt();
            if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("far tile level " + level);
            FarTile tile = new FarTile(level, tx, tz);
            tile.version = in.getLong();
            int states = in.getInt();
            if (states < 0 || states > MAX_PALETTE) throw new IllegalArgumentException("far tile palette " + states);
            tile.states = new int[Math.max(1, states)];
            for (int i = 0; i < states; i++) tile.states[i] = in.getInt();
            tile.stateCount = states;
            int biomes = in.getInt();
            if (biomes < 0 || biomes > MAX_PALETTE) throw new IllegalArgumentException("far tile biomes " + biomes);
            tile.biomes = new String[Math.max(1, biomes)];
            for (int i = 0; i < biomes; i++) {
                int n = in.getShort() & 0xFFFF;
                if (n > 256) throw new IllegalArgumentException("far tile biome name");
                byte[] name = new byte[n];
                in.get(name);
                tile.biomes[i] = new String(name, StandardCharsets.UTF_8);
            }
            tile.biomeCount = biomes;
            for (int j = 0; j < SIZE; j++) {
                short previous = 0;
                for (int i = 0; i < SIZE; i++) {
                    previous = (short) (previous + in.getShort());
                    tile.height[index(i, j)] = previous;
                }
            }
            in.get(tile.top).get(tile.under).get(tile.biome).get(tile.water);
            for (int i = 0; i < CELLS; i++) {
                if (tile.height[i] == EMPTY) continue;
                if ((tile.top[i] & 0xFF) >= states || (tile.under[i] & 0xFF) >= states || (tile.biome[i] & 0xFF) >= biomes) {
                    throw new IllegalArgumentException("far tile palette index out of range");
                }
            }
            return tile;
        } catch (DataFormatException | java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("malformed far tile", e);
        } finally {
            inflater.end();
        }
    }
}
