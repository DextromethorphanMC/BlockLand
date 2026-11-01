package com.blockland.backend.map;

import com.blockland.backend.map.nbt.Nbt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a generated structure file the way the world will treat it once pasted into the empty arena: a falling block
 * (sand, gravel, concrete powder, anvils, …) with nothing solid under it drops into the void, leaving a hole in the
 * floor; water or lava over air or at the map's side pours out into it, hanging as a sheet. Everything the file leaves
 * out is air there.
 */
public final class StructureSupport {
    /** Blocks that fall when what is under them is free (vanilla's {@code FallingBlock} family and the like). */
    private static final Set<String> FALLING = Set.of("sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel", "anvil",
            "chipped_anvil", "damaged_anvil", "dragon_egg", "scaffolding", "pointed_dripstone");
    /** What a falling block falls through. */
    private static final Set<String> FREE = Set.of("air", "cave_air", "void_air", "water", "lava", "fire", "soul_fire", "short_grass",
            "tall_grass", "fern", "large_fern", "dead_bush", "seagrass", "tall_seagrass", "vine", "glow_lichen", "light", "structure_void");

    private StructureSupport() {
    }

    /** What would leak into the void, as positions ("x,y,z name"): falling blocks that fall, fluids that pour out. */
    public static List<String> problems(byte[] data) {
        List<String> out = new ArrayList<>(unsupportedFalling(data));
        out.addAll(leakingFluids(data));
        return out;
    }

    /**
     * Water and lava that would pour into the void: nothing (air) under them, or at the map's side, where the world
     * past the file is air.
     */
    public static List<String> leakingFluids(byte[] data) {
        Parsed p = parse(data);
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : p.blocks.entrySet()) {
            String name = e.getValue();
            if (!name.equals("water") && !name.equals("lava")) continue;
            long k = e.getKey();
            int x = (int) (k >> 40), y = (int) ((k >> 20) & 0xFFFFF), z = (int) (k & 0xFFFFF);
            String below = p.blocks.get(key(x, y - 1, z));
            boolean hangs = y == 0 || below == null || below.equals("air") || below.equals("cave_air") || below.equals("structure_void");
            boolean side = x == 0 || z == 0 || x == p.sizeX - 1 || z == p.sizeZ - 1;
            // beside it an open column with nothing under: it flows over the edge and down into the void
            boolean spills = false;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (empty(p.blocks.get(key(x + d[0], y, z + d[1]))) && bottomless(p.blocks, x + d[0], y, z + d[1])) spills = true;
            }
            if (hangs || side || spills) {
                out.add(x + "," + y + "," + z + " " + name + (hangs ? " over air" : side ? " at the map's side" : " beside a drop into the void"));
            }
        }
        out.sort(null);
        return out;
    }

    private static boolean empty(String name) {
        return name == null || name.equals("air") || name.equals("cave_air") || name.equals("structure_void");
    }

    /** Nothing but air under (x, y, z) all the way down. */
    private static boolean bottomless(Map<Long, String> blocks, int x, int y, int z) {
        for (int below = y - 1; below >= 0; below--) if (!empty(blocks.get(key(x, below, z)))) return false;
        return true;
    }

    private record Parsed(int sizeX, int sizeZ, Map<Long, String> blocks) {
    }

    private static Parsed parse(byte[] data) {
        Map<String, Object> root;
        try {
            root = Nbt.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> names = new ArrayList<>();
        for (Object entry : Nbt.list(root.get("palette"))) {
            String name = String.valueOf(Nbt.compound(entry).get("Name"));
            names.add(name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name);
        }
        Map<Long, String> blocks = new HashMap<>();
        for (Object entry : Nbt.list(root.get("blocks"))) {
            Map<String, Object> block = Nbt.compound(entry);
            List<Object> pos = Nbt.list(block.get("pos"));
            int x = Nbt.intValue(pos.get(0), 0), y = Nbt.intValue(pos.get(1), 0), z = Nbt.intValue(pos.get(2), 0);
            blocks.put(key(x, y, z), names.get(Nbt.intValue(block.get("state"), 0)));
        }
        List<Object> size = Nbt.list(root.get("size"));
        return new Parsed(Nbt.intValue(size.get(0), 0), Nbt.intValue(size.get(2), 0), blocks);
    }

    /** The positions ("x,y,z name") of falling blocks that would fall: nothing under them, or only something they fall through. */
    public static List<String> unsupportedFalling(byte[] data) {
        Map<Long, String> blocks = parse(data).blocks;
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            if (!falls(e.getValue())) continue;
            long k = e.getKey();
            int x = (int) (k >> 40), y = (int) ((k >> 20) & 0xFFFFF), z = (int) (k & 0xFFFFF);
            if (!held(blocks, x, y, z)) out.add(x + "," + y + "," + z + " " + e.getValue());
        }
        out.sort(null);
        return out;
    }

    private static boolean falls(String name) {
        return FALLING.contains(name) || name.endsWith("_concrete_powder");
    }

    /** Whether the block at (x, y, z) rests on something: down its column, the first block not falling must not be free. */
    private static boolean held(Map<Long, String> blocks, int x, int y, int z) {
        for (int below = y - 1; below >= 0; below--) {
            String name = blocks.get(key(x, below, z));
            if (name == null || FREE.contains(name)) return false;
            if (!falls(name)) return true;
        }
        return false;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }
}
