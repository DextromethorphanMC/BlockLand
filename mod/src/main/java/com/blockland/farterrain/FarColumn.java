package com.blockland.farterrain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One unit of the real far chunks (rendering spec §23), shared by the server that reads it and the client that draws it
 * with Minecraft's own block renderer. A unit of scale {@code k} covers 2<sup>k</sup> × 2<sup>k</sup> chunks; its blocks
 * are cubes of 2<sup>k</sup> real blocks (scale 0: the chunk itself, block for block). Per section from {@link #minSection}
 * up (sections of 16³ such blocks), the section as Minecraft sends it to clients ({@code LevelChunkSection.write}: block
 * count, block state and biome palettes — global registry ids), and its sky and block light (2048 bytes each, null when
 * there is none). Empty (all-air) sections are null. {@link #present}: which of the unit's chunks exist (the rest are air
 * here and are drawn by something else). Not thread-safe; a unit handed to another thread is not changed any more.
 */
public final class FarColumn {
    private static final int FORMAT = 2;
    /** Largest light array (a section's nibbles). */
    public static final int LIGHT_BYTES = 2048;
    /** The coarsest unit: 64 × 64 chunks of 64-block cubes. */
    public static final int MAX_SCALE = 6;

    /** Scale (0 … {@link #MAX_SCALE}) and the unit's position in units of its scale (chunk x = cx << scale). */
    public final int scale, cx, cz, minSection;
    /** Per section: {@code LevelChunkSection.write} bytes, or null (air). */
    public final byte[][] sections;
    /** Per section: light nibbles, or null (not stored: the client derives sky light from the column). */
    public final byte[][] skyLight, blockLight;
    /**
     * Which chunks of the unit exist, bit {@code dx + dz << scale} (dx, dz chunks from the unit's corner), or null when all
     * do. A unit of scale 0 that exists is always complete.
     */
    public long[] present;
    /** Content hash ({@link #computeVersion}); 0 = not computed. */
    public long version;

    public FarColumn(int scale, int cx, int cz, int minSection, int sectionCount) {
        if (scale < 0 || scale > MAX_SCALE) throw new IllegalArgumentException("far column scale " + scale);
        this.scale = scale;
        this.cx = cx;
        this.cz = cz;
        this.minSection = minSection;
        this.sections = new byte[sectionCount][];
        this.skyLight = new byte[sectionCount][];
        this.blockLight = new byte[sectionCount][];
    }

    /** A chunk column (scale 0). */
    public FarColumn(int cx, int cz, int minSection, int sectionCount) {
        this(0, cx, cz, minSection, sectionCount);
    }

    // ---- keys -------------------------------------------------------------------------------------------------------

    /** Unit key: x in bits 0–27, z in bits 28–55 (both signed, in units of the scale), the scale in bits 56–59. */
    public static long key(int scale, int x, int z) {
        return (long) x & 0x0FFFFFFFL | ((long) z & 0x0FFFFFFFL) << 28 | (long) scale << 56;
    }

    /** A chunk column's key (scale 0). Not {@code ChunkPos.asLong}: convert where both meet. */
    public static long key(int cx, int cz) {
        return key(0, cx, cz);
    }

    public static int x(long key) {
        return (int) (key << 36 >> 36);
    }

    public static int z(long key) {
        return (int) (key << 8 >> 36);
    }

    public static int scale(long key) {
        return (int) (key >>> 56) & 15;
    }

    /** The unit's corner in chunks. */
    public static int chunkX(long key) {
        return x(key) << scale(key);
    }

    public static int chunkZ(long key) {
        return z(key) << scale(key);
    }

    /** The unit of the given scale holding a chunk. */
    public static long unitOf(int scale, int chunkX, int chunkZ) {
        return key(scale, chunkX >> scale, chunkZ >> scale);
    }

    /** The unit one scale coarser holding this one; the four one finer it holds (index dx + 2 dz). */
    public static long parent(long key) {
        int s = scale(key) + 1;
        return key(s, x(key) >> 1, z(key) >> 1);
    }

    public static long child(long key, int index) {
        int s = scale(key) - 1;
        return key(s, x(key) * 2 + (index & 1), z(key) * 2 + (index >> 1));
    }

    public long key() {
        return key(scale, cx, cz);
    }

    // ---- vertical layout --------------------------------------------------------------------------------------------

    /** The lowest section of a unit of this scale in a level whose lowest block is {@code minY} (sections of cubes). */
    public static int minSection(int minY, int scale) {
        return Math.floorDiv(Math.floorDiv(minY, 1 << scale), 16);
    }

    /** Sections of a unit of this scale covering the level's {@code minY} … {@code minY + height − 1}. */
    public static int sectionCount(int minY, int height, int scale) {
        int top = Math.floorDiv(Math.floorDiv(minY + height - 1, 1 << scale), 16);
        return top - minSection(minY, scale) + 1;
    }

    // ---- presence ---------------------------------------------------------------------------------------------------

    /** Whether the unit's chunk {@code dx, dz} (from its corner) exists. */
    public boolean present(int dx, int dz) {
        if (present == null) return true;
        int bit = dx + (dz << scale);
        return (present[bit >> 6] >>> (bit & 63) & 1) != 0;
    }

    /** Marks the chunk present (making the mask on first use, all absent). */
    public void setPresent(int dx, int dz, boolean on) {
        int side = 1 << scale;
        if (present == null) {
            if (on) return;
            present = new long[Math.max(1, side * side + 63 >> 6)];
            for (int i = 0; i < side * side; i++) present[i >> 6] |= 1L << (i & 63);
        }
        int bit = dx + (dz << scale);
        if (on) present[bit >> 6] |= 1L << (bit & 63);
        else present[bit >> 6] &= ~(1L << (bit & 63));
    }

    /** How many of the unit's chunks exist. */
    public int presentCount() {
        int side = 1 << scale;
        if (present == null) return side * side;
        int n = 0;
        for (long w : present) n += Long.bitCount(w);
        return n;
    }

    /** No section holds a block. */
    public boolean isEmpty() {
        for (byte[] s : sections) if (s != null) return false;
        return true;
    }

    public long computeVersion() {
        long h = 0x9E3779B97F4A7C15L ^ key();
        for (int i = 0; i < sections.length; i++) {
            h = mix(h ^ i ^ hash(sections[i]));
            h = mix(h ^ hash(skyLight[i]) * 31);
            h = mix(h ^ hash(blockLight[i]) * 17);
        }
        if (present != null) for (long w : present) h = mix(h ^ w);
        if (h == 0) h = 1;
        version = h;
        return h;
    }

    private static long hash(byte[] bytes) {
        if (bytes == null) return 0x5555;
        long h = bytes.length;
        for (byte b : bytes) h = h * 0x100000001B3L ^ b;
        return h;
    }

    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /**
     * Format, scale, cx, cz, min section, count, version, presence words (0 = all present) and the words; per section flags
     * (1 section, 2 sky, 4 block) and the arrays; deflated.
     */
    public byte[] encode() {
        if (version == 0) computeVersion();
        int words = present == null ? 0 : present.length;
        int size = 4 * 7 + 8 + 8 * words;
        for (int i = 0; i < sections.length; i++) {
            size += 1;
            if (sections[i] != null) size += 4 + sections[i].length;
            if (skyLight[i] != null) size += LIGHT_BYTES;
            if (blockLight[i] != null) size += LIGHT_BYTES;
        }
        ByteBuffer raw = ByteBuffer.allocate(size);
        raw.putInt(FORMAT).putInt(scale).putInt(cx).putInt(cz).putInt(minSection).putInt(sections.length).putLong(version);
        raw.putInt(words);
        for (int i = 0; i < words; i++) raw.putLong(present[i]);
        for (int i = 0; i < sections.length; i++) {
            int flags = (sections[i] != null ? 1 : 0) | (skyLight[i] != null ? 2 : 0) | (blockLight[i] != null ? 4 : 0);
            raw.put((byte) flags);
            if (sections[i] != null) raw.putInt(sections[i].length).put(sections[i]);
            if (skyLight[i] != null) raw.put(skyLight[i], 0, LIGHT_BYTES);
            if (blockLight[i] != null) raw.put(blockLight[i], 0, LIGHT_BYTES);
        }
        Deflater deflater = new Deflater(4);
        try {
            deflater.setInput(raw.array(), 0, raw.position());
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.position() / 3);
            byte[] chunk = new byte[16384];
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** The unit {@link #encode} wrote; {@link IllegalArgumentException} on anything malformed. */
    public static FarColumn decode(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] raw = new byte[64 * 1024];
            int length = 0;
            while (!inflater.finished()) {
                if (length == raw.length) {
                    if (raw.length >= 8 << 20) throw new IllegalArgumentException("far column too large");
                    raw = java.util.Arrays.copyOf(raw, raw.length * 2);
                }
                int n = inflater.inflate(raw, length, raw.length - length);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalArgumentException("truncated far column");
                length += n;
            }
            ByteBuffer in = ByteBuffer.wrap(raw, 0, length);
            if (in.getInt() != FORMAT) throw new IllegalArgumentException("unknown far column format");
            int scale = in.getInt(), cx = in.getInt(), cz = in.getInt(), min = in.getInt(), count = in.getInt();
            if (scale < 0 || scale > MAX_SCALE) throw new IllegalArgumentException("far column scale " + scale);
            if (count < 0 || count > 256) throw new IllegalArgumentException("far column of " + count + " sections");
            FarColumn column = new FarColumn(scale, cx, cz, min, count);
            column.version = in.getLong();
            int words = in.getInt(), side = 1 << scale;
            if (words != 0) {
                if (words != Math.max(1, side * side + 63 >> 6)) throw new IllegalArgumentException("far column presence of " + words + " words");
                column.present = new long[words];
                for (int i = 0; i < words; i++) column.present[i] = in.getLong();
            }
            for (int i = 0; i < count; i++) {
                int flags = in.get();
                if ((flags & 1) != 0) {
                    int n = in.getInt();
                    if (n < 0 || n > 1 << 20) throw new IllegalArgumentException("far column section of " + n + " bytes");
                    column.sections[i] = new byte[n];
                    in.get(column.sections[i]);
                }
                if ((flags & 2) != 0) {
                    column.skyLight[i] = new byte[LIGHT_BYTES];
                    in.get(column.skyLight[i]);
                }
                if ((flags & 4) != 0) {
                    column.blockLight[i] = new byte[LIGHT_BYTES];
                    in.get(column.blockLight[i]);
                }
            }
            return column;
        } catch (DataFormatException | java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("malformed far column", e);
        } finally {
            inflater.end();
        }
    }
}
