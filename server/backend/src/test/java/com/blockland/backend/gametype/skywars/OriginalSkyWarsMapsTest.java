package com.blockland.backend.gametype.skywars;

import com.blockland.backend.map.MapFileAnalyzer;
import com.blockland.backend.map.nbt.Nbt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OriginalSkyWarsMapsTest {
    private record Pos(int x, int y, int z) {
        Pos above(int n) { return new Pos(x, y + n, z); }
    }
    private record Cell(int x, int z) { }

    @Test
    void actualNbtHasSafeSpawnsLootAndExactlyTenBlockRoutes() throws Exception {
        var catalog = OriginalSkyWarsMaps.catalog();
        assertThat(catalog).hasSize(5);
        assertThat(catalog.stream().map(OriginalSkyWarsMaps.Design::id)).doesNotHaveDuplicates();
        Set<Integer> geometries = new HashSet<>();
        for (var design : catalog) {
            var arena = OriginalSkyWarsMaps.generate(design);
            assertThat(OriginalSkyWarsMaps.generate(design).data()).isEqualTo(arena.data());
            var analyzed = new MapFileAnalyzer().analyze(arena.data(), "nbt", null);
            var root = Nbt.read(new ByteArrayInputStream(arena.data()));
            List<Object> palette = Nbt.list(root.get("palette"));
            Map<Pos, String> blocks = new HashMap<>();
            for (Object raw : Nbt.list(root.get("blocks"))) {
                var block = Nbt.compound(raw);
                var p = Nbt.list(block.get("pos"));
                Pos pos = new Pos((int) p.get(0), (int) p.get(1), (int) p.get(2));
                String name = (String) Nbt.compound(palette.get((int) block.get("state"))).get("Name");
                assertThat(pos.x()).isBetween(0, OriginalSkyWarsMaps.SIZE - 1);
                assertThat(pos.z()).isBetween(0, OriginalSkyWarsMaps.SIZE - 1);
                assertThat(pos.y()).isBetween(0, OriginalSkyWarsMaps.HEIGHT - 1);
                if (!name.equals("minecraft:air")) assertThat(blocks.put(pos, name)).isNull();
                if (name.equals("minecraft:chest")) assertThat(Nbt.compound(block.get("nbt")).get("id")).isEqualTo(name);
            }
            geometries.add(blocks.keySet().hashCode());
            assertThat(analyzed.containers()).hasSize(36);
            assertThat(arena.chests()).hasSize(36);
            assertThat(arena.spawns()).hasSize(8);
            Set<Pos> chestPositions = new HashSet<>();
            for (var chest : arena.chests()) {
                Pos p = new Pos(chest.x(), chest.y(), chest.z());
                assertThat(chestPositions.add(p)).isTrue();
                assertThat(blocks.get(p)).isEqualTo("minecraft:chest");
                assertThat(blocks).containsKey(p.above(-1)).doesNotContainKey(p.above(1));
                assertThat(analyzed.containers()).contains(new MapFileAnalyzer.Block(p.x(), p.y(), p.z()));
            }
            List<Set<Cell>> islands = components(blocks);
            assertThat(islands).as(design.id()).hasSize(13);
            int tenBlockRoutes = 0;
            int[] degree = new int[13];
            for (int i = 0; i < islands.size(); i++) for (int j = i + 1; j < islands.size(); j++) {
                int distance = distanceSquared(boundary(islands.get(i)), boundary(islands.get(j)));
                assertThat(distance).as(design.id() + " islands " + i + "/" + j).isGreaterThanOrEqualTo(100);
                if (distance == 100) { tenBlockRoutes++; degree[i]++; degree[j]++; }
            }
            assertThat(tenBlockRoutes).as(design.id()).isEqualTo(16);
            assertThat(degree).containsOnly(2, 3, 4);
            for (var island : islands) {
                var loot = arena.chests().stream().filter(c -> island.contains(new Cell(c.x(), c.z()))).toList();
                var teams = arena.spawns().stream().filter(t -> island.contains(new Cell((int) t.points().getFirst().x(),
                        (int) t.points().getFirst().z()))).toList();
                if (!teams.isEmpty()) {
                    assertThat(teams).hasSize(1);
                    assertThat(loot).hasSize(3).allSatisfy(c -> assertThat(c.lootTableId()).isEqualTo("island"));
                    assertThat(teams.getFirst().points()).hasSize(4);
                    for (var spawn : teams.getFirst().points()) {
                        Pos p = new Pos((int) spawn.x(), (int) spawn.y(), (int) spawn.z());
                        assertThat(blocks).containsKey(p.above(-1)).doesNotContainKeys(p, p.above(1));
                    }
                } else {
                    assertThat(loot.size()).isIn(2, 4);
                    assertThat(loot).allSatisfy(c -> assertThat(c.lootTableId()).isEqualTo("center"));
                }
                // All chests have a reachable, two-block-high approach on the island's floor.
                Set<Cell> walkable = new HashSet<>();
                for (Cell c : island) {
                    Pos feet = new Pos(c.x(), OriginalSkyWarsMaps.TOP + 1, c.z());
                    if (blocks.containsKey(feet.above(-1)) && !blocks.containsKey(feet) && !blocks.containsKey(feet.above(1)))
                        walkable.add(c);
                }
                Cell start = teams.isEmpty() ? walkable.iterator().next() : new Cell((int) teams.getFirst().points().getFirst().x(),
                        (int) teams.getFirst().points().getFirst().z());
                Set<Cell> reachable = flood(start, walkable);
                for (var chest : loot) assertThat(neighbors(new Cell(chest.x(), chest.z())).stream().anyMatch(reachable::contains)).isTrue();
            }
            String export = System.getProperty("blockland.mapExport");
            if (export != null) {
                Path dir = Files.createDirectories(Path.of(export));
                Files.write(dir.resolve(design.id() + ".nbt"), arena.data());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve(design.id() + ".json").toFile(),
                        Map.of("id", design.id(), "name", design.name(), "gameType", "SKYWARS", "voidY", -10,
                                "teamSpawns", arena.spawns(), "settings", new SkyWarsMapSettings(arena.chests(), "island")));
            }
            System.out.println(design.id() + ": islands=13 spawn=8x3 middle=4x2 main=1x4 spawns=32 routes=16 gap=10 PASS");
        }
        assertThat(geometries).hasSize(5);
    }

    private static List<Cell> neighbors(Cell c) {
        return List.of(new Cell(c.x()-1,c.z()), new Cell(c.x()+1,c.z()), new Cell(c.x(),c.z()-1), new Cell(c.x(),c.z()+1));
    }

    private static Set<Cell> flood(Cell start, Set<Cell> cells) {
        Set<Cell> reached = new HashSet<>();
        var queue = new ArrayDeque<Cell>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Cell p = queue.remove();
            if (!cells.contains(p) || !reached.add(p)) continue;
            queue.addAll(neighbors(p));
        }
        return reached;
    }

    private static List<Set<Cell>> components(Map<Pos, String> blocks) {
        Set<Cell> cells = new HashSet<>();
        blocks.keySet().forEach(p -> cells.add(new Cell(p.x(), p.z())));
        List<Set<Cell>> result = new ArrayList<>();
        while (!cells.isEmpty()) {
            Set<Cell> component = flood(cells.iterator().next(), cells);
            cells.removeAll(component);
            result.add(component);
        }
        return result;
    }

    private static Set<Cell> boundary(Set<Cell> island) {
        Set<Cell> edge = new HashSet<>();
        for (Cell c : island) if (neighbors(c).stream().anyMatch(n -> !island.contains(n))) edge.add(c);
        return edge;
    }

    private static int distanceSquared(Set<Cell> a, Set<Cell> b) {
        int min = Integer.MAX_VALUE;
        for (Cell p : a) for (Cell q : b) {
            // Distance between block faces, not centers; a ten-block gap contains ten air columns.
            int dx = Math.max(0, Math.abs(p.x() - q.x()) - 1), dz = Math.max(0, Math.abs(p.z() - q.z()) - 1);
            min = Math.min(min, dx * dx + dz * dz);
        }
        return min;
    }
}
