package com.blockland.backend.map;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.map.nbt.Nbt;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads uploaded map files (vanilla structure {@code .nbt} or Sponge {@code .schem}) to find the map size,
 * every container (for per-chest loot configuration), spawn marker blocks and a top-down preview.
 */
@Component
public class MapFileAnalyzer {
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");
    private static final Set<String> CONTAINERS = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel");

    public record Block(int x, int y, int z) {
    }

    /** Highest block per (x, z) column; {@code cells[x + z * width]} indexes {@code palette} or is -1. */
    public record TopView(int width, int length, List<String> palette, int[] cells, int[] heights) {
    }

    public record Result(String format, GameMap.Size size, List<Block> containers, List<Block> markers, TopView topView) {
    }

    public static String detectFormat(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".schem")) return "schem";
        if (lower.endsWith(".nbt")) return "nbt";
        throw ApiException.badRequest("仅支持 .nbt（原版结构）和 .schem（WorldEdit / Sponge Schematic）地图文件");
    }

    public Result analyze(byte[] data, String format, String markerBlock) {
        Map<String, Object> root;
        try {
            root = Nbt.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw ApiException.badRequest("无法解析地图文件: " + e.getMessage());
        }
        String marker = normalizeId(markerBlock);
        Collector collector = switch (format) {
            case "nbt" -> readStructure(root);
            case "schem" -> readSchematic(root);
            default -> throw ApiException.badRequest("不支持的地图格式: " + format);
        };
        List<Block> containers = new ArrayList<>();
        List<Block> markers = new ArrayList<>();
        for (int i = 0; i < collector.names.size(); i++) {
            String name = collector.names.get(i);
            int[] p = collector.positions.get(i);
            if (CONTAINERS.contains(name)) containers.add(new Block(p[0], p[1], p[2]));
            if (marker != null && marker.equals(name)) markers.add(new Block(p[0], p[1], p[2]));
        }
        containers.sort(Comparator.comparingInt(Block::y).thenComparingInt(Block::x).thenComparingInt(Block::z));
        return new Result(format, collector.size, containers, markers, collector.topView(marker));
    }

    /** Groups spawn markers that are close to each other into one team spawn (one island = one team). */
    public static List<GameMap.TeamSpawn> clusterMarkers(List<Block> markers, GameMap.Size size) {
        int n = markers.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Block a = markers.get(i);
                Block b = markers.get(j);
                int dx = a.x() - b.x(), dz = a.z() - b.z(), dy = Math.abs(a.y() - b.y());
                if (dx * dx + dz * dz <= 144 && dy <= 12) union(parent, i, j);
            }
        }
        Map<Integer, List<Block>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(markers.get(i));

        double cx = size.x() / 2.0, cz = size.z() / 2.0;
        List<List<Block>> ordered = new ArrayList<>(groups.values());
        // order islands clockwise around the map centre so "evenly spaced" picks opposite islands
        ordered.sort(Comparator.comparingDouble(g -> {
            double ax = g.stream().mapToInt(Block::x).average().orElse(0) - cx;
            double az = g.stream().mapToInt(Block::z).average().orElse(0) - cz;
            return Math.atan2(az, ax);
        }));
        List<GameMap.TeamSpawn> spawns = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            List<GameMap.SpawnPoint> points = ordered.get(i).stream()
                    .map(b -> new GameMap.SpawnPoint(b.x(), b.y(), b.z(), yawTowards(b, cx, cz)))
                    .toList();
            spawns.add(new GameMap.TeamSpawn("出生岛 " + (i + 1), points));
        }
        return spawns;
    }

    /** Yaw (degrees, Minecraft convention) of a player standing on {@code b} and facing the point (cx, cz). */
    public static float yawTowards(Block b, double cx, double cz) {
        double dx = cx - (b.x() + 0.5), dz = cz - (b.z() + 0.5);
        if (dx * dx + dz * dz < 1) return 0;
        return (float) Math.round(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    // ---- formats ---------------------------------------------------------------------------

    private Collector readStructure(Map<String, Object> root) {
        List<Object> sizeList = Nbt.list(root.get("size"));
        if (sizeList.size() < 3) throw ApiException.badRequest("不是有效的结构文件（缺少 size）");
        GameMap.Size size = new GameMap.Size(Nbt.intValue(sizeList.get(0), 0), Nbt.intValue(sizeList.get(1), 0), Nbt.intValue(sizeList.get(2), 0));
        List<Object> palette = Nbt.list(root.get("palette"));
        if (palette.isEmpty()) {
            List<Object> palettes = Nbt.list(root.get("palettes"));
            if (!palettes.isEmpty()) palette = Nbt.list(palettes.get(0));
        }
        List<String> names = new ArrayList<>();
        for (Object entry : palette) names.add(normalizeId(String.valueOf(Nbt.compound(entry).get("Name"))));

        Collector collector = new Collector(size);
        for (Object entry : Nbt.list(root.get("blocks"))) {
            Map<String, Object> block = Nbt.compound(entry);
            int state = Nbt.intValue(block.get("state"), -1);
            List<Object> pos = Nbt.list(block.get("pos"));
            if (state < 0 || state >= names.size() || pos.size() < 3) continue;
            collector.add(names.get(state), Nbt.intValue(pos.get(0), 0), Nbt.intValue(pos.get(1), 0), Nbt.intValue(pos.get(2), 0));
        }
        return collector;
    }

    private Collector readSchematic(Map<String, Object> root) {
        Map<String, Object> schematic = root.containsKey("Schematic") ? Nbt.compound(root.get("Schematic")) : root;
        int width = Nbt.intValue(schematic.get("Width"), 0) & 0xFFFF;
        int height = Nbt.intValue(schematic.get("Height"), 0) & 0xFFFF;
        int length = Nbt.intValue(schematic.get("Length"), 0) & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) throw ApiException.badRequest("不是有效的 schematic 文件（缺少尺寸）");

        Map<String, Object> paletteTag;
        Object dataTag;
        if (schematic.containsKey("Blocks")) {
            Map<String, Object> blocks = Nbt.compound(schematic.get("Blocks"));
            paletteTag = Nbt.compound(blocks.get("Palette"));
            dataTag = blocks.get("Data");
        } else {
            paletteTag = Nbt.compound(schematic.get("Palette"));
            dataTag = schematic.get("BlockData");
        }
        if (!(dataTag instanceof byte[] data)) throw ApiException.badRequest("schematic 缺少方块数据");
        Map<Integer, String> palette = new HashMap<>();
        paletteTag.forEach((state, index) -> palette.put(Nbt.intValue(index, -1), normalizeId(state)));

        Collector collector = new Collector(new GameMap.Size(width, height, length));
        int index = 0;
        int cursor = 0;
        while (cursor < data.length) {
            int value = 0, shift = 0;
            byte b;
            do {
                b = data[cursor++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && cursor < data.length);
            int y = index / (width * length);
            int rem = index % (width * length);
            collector.add(palette.getOrDefault(value, "minecraft:air"), rem % width, y, rem / width);
            index++;
        }
        return collector;
    }

    /** "minecraft:chest[facing=north]" / "chest" → "minecraft:chest". */
    private static String normalizeId(String state) {
        if (state == null || state.isBlank()) return null;
        String id = state.trim().toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static final class Collector {
        final GameMap.Size size;
        final List<String> names = new ArrayList<>();
        final List<int[]> positions = new ArrayList<>();

        Collector(GameMap.Size size) {
            this.size = size;
        }

        void add(String name, int x, int y, int z) {
            if (name == null || AIR.contains(name)) return;
            names.add(name);
            positions.add(new int[]{x, y, z});
        }

        TopView topView(String marker) {
            int w = Math.max(1, size.x()), l = Math.max(1, size.z());
            int[] cells = new int[w * l];
            int[] heights = new int[w * l];
            java.util.Arrays.fill(cells, -1);
            java.util.Arrays.fill(heights, Integer.MIN_VALUE);
            List<String> palette = new ArrayList<>();
            Map<String, Integer> paletteIndex = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (name.equals(marker)) continue;
                int[] p = positions.get(i);
                if (p[0] < 0 || p[0] >= w || p[2] < 0 || p[2] >= l) continue;
                int cell = p[0] + p[2] * w;
                if (p[1] > heights[cell]) {
                    heights[cell] = p[1];
                    cells[cell] = paletteIndex.computeIfAbsent(name, n -> {
                        palette.add(n);
                        return palette.size() - 1;
                    });
                }
            }
            for (int i = 0; i < heights.length; i++) if (heights[i] == Integer.MIN_VALUE) heights[i] = -1;
            return new TopView(w, l, palette, cells, heights);
        }
    }
}
