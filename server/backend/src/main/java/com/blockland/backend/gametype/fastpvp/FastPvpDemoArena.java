package com.blockland.backend.gametype.fastpvp;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.List;

/** Generated demo FastPVP map: a walled stone arena with cover pillars and two opposing spawn sides. */
public final class FastPvpDemoArena {
    public static final int SIZE_X = 33;
    public static final int SIZE_Y = 8;
    public static final int SIZE_Z = 21;

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns) {
    }

    private FastPvpDemoArena() {
    }

    public static Result generate() {
        StructureBuilder b = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);
        b.fill(0, 0, 0, SIZE_X - 1, 0, SIZE_Z - 1, "minecraft:smooth_stone");
        // centre line and spawn pads
        b.fill(16, 0, 1, 16, 0, SIZE_Z - 2, "minecraft:polished_andesite");
        b.fill(2, 0, 5, 5, 0, 15, "minecraft:red_concrete");
        b.fill(27, 0, 5, 30, 0, 15, "minecraft:blue_concrete");
        // walls
        String wall = "minecraft:stone_bricks";
        b.fill(0, 1, 0, SIZE_X - 1, 4, 0, wall);
        b.fill(0, 1, SIZE_Z - 1, SIZE_X - 1, 4, SIZE_Z - 1, wall);
        b.fill(0, 1, 0, 0, 4, SIZE_Z - 1, wall);
        b.fill(SIZE_X - 1, 1, 0, SIZE_X - 1, 4, SIZE_Z - 1, wall);
        // cover
        String pillar = "minecraft:polished_deepslate";
        for (int[] p : new int[][]{{11, 5}, {11, 15}, {21, 5}, {21, 15}}) b.fill(p[0], 1, p[1], p[0], 3, p[1], pillar);
        b.fill(15, 1, 9, 17, 2, 11, "minecraft:mossy_stone_bricks");

        float east = -90f, west = 90f;
        List<GameMap.TeamSpawn> spawns = List.of(
                new GameMap.TeamSpawn("红方", List.of(
                        new GameMap.SpawnPoint(4, 1, 10, east), new GameMap.SpawnPoint(4, 1, 7, east),
                        new GameMap.SpawnPoint(4, 1, 13, east), new GameMap.SpawnPoint(3, 1, 10, east))),
                new GameMap.TeamSpawn("蓝方", List.of(
                        new GameMap.SpawnPoint(28, 1, 10, west), new GameMap.SpawnPoint(28, 1, 7, west),
                        new GameMap.SpawnPoint(28, 1, 13, west), new GameMap.SpawnPoint(29, 1, 10, west))));
        return new Result(b.build(), spawns);
    }
}
