package com.blockland.backend.gametype.bomb;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Generated demo 枪战爆破 map: a walled yard, mirror-symmetric about its middle (x), the attackers' spawn at the north end
 * (low z), the defenders' at the south end, two lanes divided by walls with doorways leading to bomb sites A (west) and
 * B (east) in front of the defenders, crates on the sites and a box in the middle for cover.
 */
public final class BombDemoArena {
    public static final int SIZE_X = 48;
    public static final int SIZE_Y = 12;
    public static final int SIZE_Z = 48;

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns, List<BombMapSettings.Site> sites) {
    }

    private BombDemoArena() {
    }

    public static Result generate() {
        StructureBuilder b = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);
        b.fill(0, 0, 0, SIZE_X - 1, 0, SIZE_Z - 1, "minecraft:smooth_stone");
        // outer walls
        String wall = "minecraft:stone_bricks";
        b.fill(0, 1, 0, SIZE_X - 1, 5, 0, wall);
        b.fill(0, 1, SIZE_Z - 1, SIZE_X - 1, 5, SIZE_Z - 1, wall);
        b.fill(0, 1, 0, 0, 5, SIZE_Z - 1, wall);
        b.fill(SIZE_X - 1, 1, 0, SIZE_X - 1, 5, SIZE_Z - 1, wall);
        // spawn pads
        b.fill(18, 0, 2, 29, 0, 6, "minecraft:red_concrete");
        b.fill(18, 0, 41, 29, 0, 45, "minecraft:blue_concrete");

        // lane walls with a doorway each, and the middle box
        for (int x : new int[]{16, SIZE_X - 1 - 16}) {
            b.fill(x, 1, 10, x, 4, 16, "minecraft:polished_andesite");
            b.fill(x, 1, 20, x, 4, 26, "minecraft:polished_andesite");
        }
        b.fill(22, 1, 20, 25, 3, 23, "minecraft:polished_deepslate");
        b.fill(20, 1, 32, 27, 2, 32, "minecraft:mossy_stone_bricks");

        // the sites: marked floors with crates
        List<BombMapSettings.Site> sites = new ArrayList<>();
        sites.add(site(b, "A", 5, 30, false));
        sites.add(site(b, "B", SIZE_X - 1 - 14, 30, true));

        // lights set into the floor
        for (int[] p : new int[][]{{8, 20}, {39, 20}, {23, 10}, {24, 37}, {10, 40}, {37, 40}}) {
            b.set(p[0], 0, p[1], "minecraft:sea_lantern");
        }

        List<GameMap.SpawnPoint> attackers = new ArrayList<>(), defenders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int x = 20 + i * 2;
            // yaw 0 faces +z (towards the sites), 180 faces -z
            attackers.add(new GameMap.SpawnPoint(x, 1, 4, 0f));
            defenders.add(new GameMap.SpawnPoint(x, 1, 43, 180f));
        }
        List<GameMap.TeamSpawn> spawns = List.of(new GameMap.TeamSpawn("进攻方", attackers), new GameMap.TeamSpawn("防守方", defenders));
        return new Result(b.build(), spawns, sites);
    }

    /** A 10 × 9 site with its west corner at (x, z): an orange floor, a TNT mark in the middle and two crates. */
    private static BombMapSettings.Site site(StructureBuilder b, String name, int x, int z, boolean mirrored) {
        b.fill(x, 0, z, x + 9, 0, z + 8, "minecraft:orange_terracotta");
        b.set(x + 4, 0, z + 4, "minecraft:tnt");
        int crate = mirrored ? x + 7 : x + 1;
        b.fill(crate, 1, z + 1, crate + 1, 2, z + 2, "minecraft:spruce_planks");
        b.fill(mirrored ? x + 1 : x + 8, 1, z + 6, mirrored ? x + 1 : x + 8, 1, z + 7, "minecraft:barrel");
        return new BombMapSettings.Site(name, x, 1, z, x + 9, 3, z + 8);
    }
}
