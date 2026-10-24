package com.blockland.backend.gametype.fastpvp;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Generated demo Rush map: two stone-and-wool platforms facing each other across a {@value #GAP}-block gap of void
 * (along z), each with a low wall of cover and a few blocks to stand behind. Team spawn group 1 stands on the north
 * platform (low z), group 2 on the south one; knocked off, a player falls into the void below.
 */
public final class RushDemoArena {
    public static final int SIZE_X = 15;
    public static final int SIZE_Y = 12;
    public static final int GAP = 14;
    private static final int PLATFORM = 11;
    public static final int SIZE_Z = PLATFORM * 2 + GAP;
    /** Top layer of the platforms (relative y); the void line is well below it. */
    static final int FLOOR = 5;
    public static final int VOID_Y = 0;

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns) {
    }

    private RushDemoArena() {
    }

    public static Result generate() {
        StructureBuilder b = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);
        platform(b, 0, "minecraft:red_wool", false);
        platform(b, SIZE_Z - PLATFORM, "minecraft:blue_wool", true);

        List<GameMap.SpawnPoint> north = new ArrayList<>(), south = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int x = 6 + i * 2;
            // yaw 0 faces +z (across the gap), 180 faces -z
            north.add(new GameMap.SpawnPoint(x, FLOOR + 1, 3, 0f));
            south.add(new GameMap.SpawnPoint(x, FLOOR + 1, SIZE_Z - 4, 180f));
        }
        return new Result(b.build(), List.of(new GameMap.TeamSpawn("北岛", north), new GameMap.TeamSpawn("南岛", south)));
    }

    /** A PLATFORM-deep island starting at z0: a stone base tapering downwards, a wool top, edge cover facing the gap. */
    private static void platform(StructureBuilder b, int z0, String wool, boolean south) {
        int z1 = z0 + PLATFORM - 1;
        b.fill(1, FLOOR - 1, z0, SIZE_X - 2, FLOOR - 1, z1, "minecraft:stone");
        b.fill(3, FLOOR - 2, z0 + 2, SIZE_X - 4, FLOOR - 2, z1 - 2, "minecraft:cobblestone");
        b.fill(5, FLOOR - 3, z0 + 4, SIZE_X - 6, FLOOR - 3, z1 - 4, "minecraft:andesite");
        b.fill(1, FLOOR, z0, SIZE_X - 2, FLOOR, z1, wool);
        // a bridge stub at the gap side, and two low blocks of cover
        int edge = south ? z0 : z1;
        int step = south ? -1 : 1;
        b.fill(6, FLOOR, edge + step, 8, FLOOR, edge + 2 * step, "minecraft:white_wool");
        int cover = south ? z0 + 3 : z1 - 3;
        b.fill(2, FLOOR + 1, cover, 4, FLOOR + 1, cover, "minecraft:oak_planks");
        b.fill(SIZE_X - 5, FLOOR + 1, cover, SIZE_X - 3, FLOOR + 1, cover, "minecraft:oak_planks");
        b.set(7, FLOOR, south ? z1 - 1 : z0 + 1, "minecraft:glowstone");
    }
}
