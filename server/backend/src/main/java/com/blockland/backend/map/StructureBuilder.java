package com.blockland.backend.map;

import com.blockland.backend.map.nbt.Nbt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds a vanilla structure file ({@code .nbt}) in code, e.g. for generated demo maps. */
public final class StructureBuilder {
    /** Minecraft 1.21.8. */
    private static final int DATA_VERSION = 4440;

    private final int sizeX, sizeY, sizeZ;
    private final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    private final Nbt.NbtList palette = new Nbt.NbtList((byte) 10);
    private final Map<Long, Object[]> blocks = new LinkedHashMap<>();

    public StructureBuilder(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    /** @param state block state in command syntax, e.g. {@code minecraft:chest[facing=north]} */
    public StructureBuilder set(int x, int y, int z, String state) {
        return set(x, y, z, state, null);
    }

    public StructureBuilder set(int x, int y, int z, String state, Map<String, Object> blockEntity) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return this;
        int index = paletteIndex.computeIfAbsent(state, this::addPaletteEntry);
        blocks.put(((long) x << 32) | ((long) y << 16) | z, new Object[]{x, y, z, index, blockEntity});
        return this;
    }

    public StructureBuilder fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, state);
        return this;
    }

    public byte[] build() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", DATA_VERSION);
        root.put("size", intList(sizeX, sizeY, sizeZ));
        root.put("palette", palette);
        Nbt.NbtList list = new Nbt.NbtList((byte) 10);
        for (Object[] b : blocks.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pos", intList((int) b[0], (int) b[1], (int) b[2]));
            entry.put("state", b[3]);
            if (b[4] != null) entry.put("nbt", b[4]);
            list.add(entry);
        }
        root.put("blocks", list);
        root.put("entities", new Nbt.NbtList((byte) 10));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Nbt.writeGzip(out, root);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int addPaletteEntry(String state) {
        Map<String, Object> entry = new LinkedHashMap<>();
        int bracket = state.indexOf('[');
        entry.put("Name", bracket < 0 ? state : state.substring(0, bracket));
        if (bracket >= 0) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
                String[] kv = pair.split("=", 2);
                properties.put(kv[0].trim(), kv[1].trim());
            }
            entry.put("Properties", properties);
        }
        palette.add(entry);
        return palette.size() - 1;
    }

    private static Nbt.NbtList intList(int... values) {
        Nbt.NbtList list = new Nbt.NbtList((byte) 3);
        for (int v : values) list.add(v);
        return list;
    }
}
