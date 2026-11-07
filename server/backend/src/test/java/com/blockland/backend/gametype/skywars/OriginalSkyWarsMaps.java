package com.blockland.backend.gametype.skywars;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Original arenas are data; this builder only knows footprints, boxes and quarter-turns. */
public final class OriginalSkyWarsMaps {
    public static final int SIZE = 128, HEIGHT = 48, TOP = 16;
    private static final int ORIGIN = SIZE / 2;

    public record Box(int x1, int y1, int z1, int x2, int y2, int z2, String state) { }
    public record Point(int x, int z) { }
    public record Island(int width, int depth, int cornerCut, String surface, String rim, String core,
                         List<Box> features, List<Point> chests, List<Point> spawns) { }
    public record Design(String id, String name, int gap, Island spawn, Island middle, Island main) { }
    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns, List<SkyWarsMapSettings.Chest> chests) { }

    private OriginalSkyWarsMaps() { }

    public static List<Design> catalog() {
        try (var input = OriginalSkyWarsMaps.class.getResourceAsStream("/maps/skywars/originals.json")) {
            if (input == null) throw new IllegalStateException("Missing original SkyWars map catalog");
            return new ObjectMapper().readValue(input, new TypeReference<List<Design>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Result generate(Design design) {
        var build = new Build();
        build.island(design.main(), -design.main().width() / 2, -design.main().depth() / 2, 0, "main", "center");
        int middleX = design.main().width() / 2 + design.gap();
        int spawnX = middleX + design.middle().width() + design.gap();
        for (int turn = 0; turn < 4; turn++) {
            build.island(design.middle(), middleX, -design.middle().depth() / 2, turn, "middle-" + turn, "center");
            build.island(design.spawn(), spawnX, -design.spawn().depth() - design.gap() / 2, turn,
                    "spawn-" + (turn * 2 + 1), "island");
            build.island(design.spawn(), spawnX, design.gap() / 2, turn,
                    "spawn-" + (turn * 2 + 2), "island");
        }
        return new Result(build.builder.build(), List.copyOf(build.spawns), List.copyOf(build.chests));
    }

    private static final class Build {
        private final StructureBuilder builder = new StructureBuilder(SIZE, HEIGHT, SIZE);
        private final List<GameMap.TeamSpawn> spawns = new ArrayList<>();
        private final List<SkyWarsMapSettings.Chest> chests = new ArrayList<>();

        private void island(Island island, int ox, int oz, int turn, String label, String loot) {
            for (int x = 0; x < island.width(); x++) for (int z = 0; z < island.depth(); z++) {
                int edgeX = Math.min(x, island.width() - 1 - x), edgeZ = Math.min(z, island.depth() - 1 - z);
                if (edgeX + edgeZ < island.cornerCut()) continue;
                set(ox + x, TOP, oz + z, turn, edgeX == 0 || edgeZ == 0
                        || edgeX + edgeZ == island.cornerCut() ? island.rim() : island.surface());
                int thickness = Math.min(12, 2 + Math.min(edgeX, edgeZ));
                for (int d = 1; d <= thickness; d++) set(ox + x, TOP - d, oz + z, turn, island.core());
            }
            for (Box box : island.features()) {
                for (int x = box.x1(); x <= box.x2(); x++) for (int y = box.y1(); y <= box.y2(); y++)
                    for (int z = box.z1(); z <= box.z2(); z++) set(ox + x, TOP + y, oz + z, turn, box.state());
            }
            for (Point chest : island.chests()) {
                Point p = rotate(ox + chest.x(), oz + chest.z(), turn);
                String facing = List.of("west", "north", "east", "south").get(turn);
                builder.set(p.x(), TOP + 1, p.z(), "minecraft:chest[facing=" + facing + ",type=single,waterlogged=false]",
                        Map.of("id", "minecraft:chest"));
                chests.add(new SkyWarsMapSettings.Chest(p.x(), TOP + 1, p.z(), loot, label));
            }
            if (!island.spawns().isEmpty()) {
                List<GameMap.SpawnPoint> points = island.spawns().stream().map(point -> {
                    Point p = rotate(ox + point.x(), oz + point.z(), turn);
                    return new GameMap.SpawnPoint(p.x(), TOP + 1, p.z(), (90 + turn * 90) % 360);
                }).toList();
                spawns.add(new GameMap.TeamSpawn(label, points));
            }
        }

        private void set(int x, int y, int z, int turn, String state) {
            Point p = rotate(x, z, turn);
            builder.set(p.x(), y, p.z(), state);
        }
    }

    private static Point rotate(int x, int z, int turn) {
        for (int i = 0; i < turn; i++) {
            int oldX = x;
            x = -z - 1;
            z = oldX;
        }
        return new Point(ORIGIN + x, ORIGIN + z);
    }
}
