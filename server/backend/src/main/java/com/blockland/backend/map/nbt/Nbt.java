package com.blockland.backend.map.nbt;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal NBT codec (Java edition, big endian). Compounds become {@code Map<String, Object>},
 * lists become {@link NbtList}, arrays stay primitive arrays.
 */
public final class Nbt {
    private static final int MAX_DEPTH = 512;

    private Nbt() {
    }

    /** List tag that remembers its element type so empty lists round-trip. */
    public static final class NbtList extends ArrayList<Object> {
        private final byte elementType;

        public NbtList(byte elementType) {
            this.elementType = elementType;
        }

        public byte elementType() {
            return elementType;
        }
    }

    /** Reads a (optionally gzip compressed) NBT file and returns its root compound. */
    public static Map<String, Object> read(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        InputStream source = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(buffered) : buffered;
        DataInputStream in = new DataInputStream(source);
        byte type = in.readByte();
        if (type != 10) throw new IOException("Root tag is not a compound");
        in.readUTF();
        return readCompound(in, 0);
    }

    public static void writeGzip(OutputStream output, Map<String, Object> root) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(output))) {
            out.writeByte(10);
            out.writeUTF("");
            writeCompound(out, root);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public static List<Object> list(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    public static int intValue(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    // ---- reading ---------------------------------------------------------------------------

    private static Map<String, Object> readCompound(DataInput in, int depth) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] data = new byte[checkLength(in.readInt())];
                in.readFully(data);
                yield data;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                byte elementType = in.readByte();
                int length = checkLength(in.readInt());
                NbtList list = new NbtList(elementType);
                for (int i = 0; i < length; i++) list.add(readPayload(in, elementType, depth + 1));
                yield list;
            }
            case 10 -> readCompound(in, depth);
            case 11 -> {
                int[] data = new int[checkLength(in.readInt())];
                for (int i = 0; i < data.length; i++) data[i] = in.readInt();
                yield data;
            }
            case 12 -> {
                long[] data = new long[checkLength(in.readInt())];
                for (int i = 0; i < data.length; i++) data[i] = in.readLong();
                yield data;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int checkLength(int length) throws IOException {
        if (length < 0 || length > 256 * 1024 * 1024) throw new IOException("Invalid NBT length " + length);
        return length;
    }

    // ---- writing ---------------------------------------------------------------------------

    private static void writeCompound(DataOutput out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            byte type = typeOf(entry.getValue());
            out.writeByte(type);
            out.writeUTF(entry.getKey());
            writePayload(out, entry.getValue());
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutput out, Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] bytes -> {
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case String s -> out.writeUTF(s);
            case List<?> list -> {
                byte elementType = list instanceof NbtList nbtList ? nbtList.elementType()
                        : list.isEmpty() ? 0 : typeOf(list.get(0));
                out.writeByte(elementType);
                out.writeInt(list.size());
                for (Object element : list) writePayload(out, element);
            }
            case Map<?, ?> map -> writeCompound(out, (Map<String, Object>) map);
            case int[] ints -> {
                out.writeInt(ints.length);
                for (int i : ints) out.writeInt(i);
            }
            case long[] longs -> {
                out.writeInt(longs.length);
                for (long l : longs) out.writeLong(l);
            }
            default -> throw new IOException("Unsupported NBT value " + value.getClass());
        }
    }

    private static byte typeOf(Object value) throws IOException {
        return switch (value) {
            case Byte b -> 1;
            case Short s -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case Float f -> 5;
            case Double d -> 6;
            case byte[] bytes -> 7;
            case String s -> 8;
            case List<?> list -> 9;
            case Map<?, ?> map -> 10;
            case int[] ints -> 11;
            case long[] longs -> 12;
            default -> throw new IOException("Unsupported NBT value " + value.getClass());
        };
    }
}
