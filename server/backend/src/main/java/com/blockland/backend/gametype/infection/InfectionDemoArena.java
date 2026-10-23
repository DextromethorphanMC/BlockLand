package com.blockland.backend.gametype.infection;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generated demo 枪战感染 map, an abandoned quarantine station: the humans' camp behind sandbags in the north, a ruined
 * street with two buildings (walkable roofs), a raised centre platform, containers and wrecks as cover, and the
 * overgrown infected zone with glowing tanks in the south. The middle part is point symmetric around the centre.
 * Role spawns: HUMAN in the camp, MONSTER in the infected zone, two BOTH points beside the centre platform. The team
 * spawns hold the same camp / infected zone points (the mod's fallback when a map has no role spawns).
 */
public final class InfectionDemoArena {
    public static final int SIZE_X = 41;
    public static final int SIZE_Y = 9;
    public static final int SIZE_Z = 41;
    /** Last block index on x and z; the point symmetric copy of (x, z) is (MAX - x, MAX - z). */
    private static final int MAX = SIZE_X - 1;

    private static final String SANDBAG = "minecraft:mud_bricks";
    private static final String LIGHT = "minecraft:verdant_froglight";
    private static final String CRATE = "minecraft:spruce_planks";

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns, List<InfectionMapSettings.Spawn> roleSpawns) {
    }

    private final StructureBuilder b = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);

    private InfectionDemoArena() {
    }

    public static Result generate() {
        return new InfectionDemoArena().build();
    }

    private Result build() {
        floor();
        walls();
        camp();
        building();
        platform();
        cover();
        infectedZone();

        // humans face south into the arena, monsters north towards the camp
        List<GameMap.SpawnPoint> humans = new ArrayList<>();
        for (int z : new int[]{4, 6}) for (int x = 16; x <= 24; x += 2) humans.add(new GameMap.SpawnPoint(x, 1, z, 0f));
        List<GameMap.SpawnPoint> monsters = new ArrayList<>();
        for (int z : new int[]{34, 36}) for (int x = 16; x <= 24; x += 4) monsters.add(new GameMap.SpawnPoint(x, 1, z, 180f));
        List<GameMap.TeamSpawn> spawns = List.of(new GameMap.TeamSpawn("人类出生点", humans), new GameMap.TeamSpawn("怪物复活点", monsters));

        List<InfectionMapSettings.Spawn> roleSpawns = new ArrayList<>();
        for (GameMap.SpawnPoint p : humans) roleSpawns.add(new InfectionMapSettings.Spawn(p.x(), p.y(), p.z(), p.yaw(), InfectionMapSettings.Role.HUMAN));
        for (GameMap.SpawnPoint p : monsters) roleSpawns.add(new InfectionMapSettings.Spawn(p.x(), p.y(), p.z(), p.yaw(), InfectionMapSettings.Role.MONSTER));
        // on the road north and south of the centre platform, facing it
        roleSpawns.add(new InfectionMapSettings.Spawn(21, 1, 16, 0f, InfectionMapSettings.Role.BOTH));
        roleSpawns.add(new InfectionMapSettings.Spawn(19, 1, 24, 180f, InfectionMapSettings.Role.BOTH));
        return new Result(b.build(), spawns, roleSpawns);
    }

    private void floor() {
        b.fill(0, 0, 0, MAX, 0, MAX, "minecraft:polished_andesite");
        b.fill(18, 0, 11, 22, 0, 29, "minecraft:gray_concrete");
        b.fill(1, 0, 1, MAX - 1, 0, 9, "minecraft:smooth_stone");
        b.fill(15, 0, 3, 25, 0, 7, "minecraft:light_gray_concrete");
        // quarantine line in front of the camp
        for (int x = 1; x < MAX; x++) b.set(x, 0, 10, (x / 2) % 2 == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete");
    }

    private void walls() {
        String wall = "minecraft:deepslate_bricks";
        b.fill(0, 1, 0, MAX, 6, 0, wall);
        b.fill(0, 1, MAX, MAX, 6, MAX, wall);
        b.fill(0, 1, 0, 0, 6, MAX, wall);
        b.fill(MAX, 1, 0, MAX, 6, MAX, wall);
        b.fill(0, 7, 0, MAX, 7, 0, "minecraft:deepslate_tiles");
        b.fill(0, 7, MAX, MAX, 7, MAX, "minecraft:deepslate_tiles");
        b.fill(0, 7, 0, 0, 7, MAX, "minecraft:deepslate_tiles");
        b.fill(MAX, 7, 0, MAX, 7, MAX, "minecraft:deepslate_tiles");
        for (int i = 4; i < MAX; i += 8) {
            b.set(i, 4, 0, LIGHT).set(i, 4, MAX, LIGHT).set(0, 4, i, LIGHT).set(MAX, 4, i, LIGHT);
        }
    }

    /** Sandbag line (jumpable) with a gap in the middle and two supply containers in the corners. */
    private void camp() {
        b.fill(6, 1, 9, 16, 1, 9, SANDBAG);
        b.fill(24, 1, 9, 34, 1, 9, SANDBAG);
        b.fill(6, 2, 9, 7, 2, 9, SANDBAG);
        b.fill(33, 2, 9, 34, 2, 9, SANDBAG);
        b.fill(3, 1, 2, 7, 3, 4, "minecraft:white_terracotta");
        b.fill(33, 1, 2, 37, 3, 4, "minecraft:white_terracotta");
        b.fill(3, 2, 2, 7, 2, 2, "minecraft:lime_terracotta");
        b.fill(33, 2, 2, 37, 2, 2, "minecraft:lime_terracotta");
    }

    /**
     * West building (x 3-11, z 13-21) and its rotated copy in the east: one room with doors and windows, a flat roof
     * with a parapet and an outside staircase up to it.
     */
    private void building() {
        String brick = "minecraft:stone_bricks";
        both(3, 1, 13, 11, 3, 21, brick);
        both(4, 1, 14, 10, 3, 20, "minecraft:air");
        both(4, 0, 14, 10, 0, 20, "minecraft:cracked_stone_bricks");
        both(3, 2, 15, 3, 2, 16, "minecraft:air");
        both(3, 2, 18, 3, 2, 19, "minecraft:air");
        both(11, 1, 15, 11, 2, 16, "minecraft:air");
        both(6, 1, 21, 7, 2, 21, "minecraft:air");
        both(9, 2, 13, 10, 2, 13, "minecraft:air");
        // roof (walk on y 5) with a parapet, open where the stairs arrive
        both(3, 4, 13, 11, 4, 21, "minecraft:polished_andesite");
        both(7, 4, 17, 7, 4, 17, LIGHT);
        both(3, 5, 13, 11, 5, 13, brick);
        both(3, 5, 21, 11, 5, 21, brick);
        both(3, 5, 13, 3, 5, 21, brick);
        both(11, 5, 13, 11, 5, 21, brick);
        both(7, 5, 13, 8, 5, 13, "minecraft:air");
        // outside staircase along the north wall, climbing east to a landing at x 8
        for (int i = 0; i < 4; i++) {
            if (i > 0) both(4 + i, 1, 12, 4 + i, i, 12, brick);
            both(4 + i, 1 + i, 12, 4 + i, 1 + i, 12, "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }
        both(8, 1, 12, 8, 4, 12, brick);
    }

    /** Raised platform in the centre (walk on y 4), open underneath, with ramps on the west and east side. */
    private void platform() {
        String deck = "minecraft:polished_andesite";
        b.fill(16, 3, 17, 24, 3, 23, deck);
        for (int[] p : new int[][]{{16, 17}, {24, 17}, {16, 23}, {24, 23}, {20, 17}, {20, 23}}) {
            b.fill(p[0], 1, p[1], p[0], 2, p[1], "minecraft:polished_deepslate");
        }
        b.fill(16, 4, 17, 24, 4, 17, "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]");
        b.fill(16, 4, 23, 24, 4, 23, "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]");
        b.set(18, 4, 19, CRATE).set(22, 4, 21, CRATE).set(20, 4, 20, LIGHT);
        for (int i = 0; i < 3; i++) {
            if (i > 0) both(13 + i, 1, 19, 13 + i, i, 21, deck);
            both(13 + i, 1 + i, 19, 13 + i, 1 + i, 21, "minecraft:polished_andesite_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }
    }

    /** Containers, crates, wrecked cars and low barricades, each mirrored to the other half. */
    private void cover() {
        both(30, 1, 12, 34, 3, 14, "minecraft:green_terracotta");
        both(30, 3, 12, 34, 3, 12, "minecraft:lime_terracotta");
        both(13, 1, 25, 14, 2, 26, CRATE);
        both(15, 1, 26, 15, 1, 26, CRATE);
        both(18, 1, 12, 19, 1, 15, "minecraft:red_terracotta");
        both(18, 2, 13, 19, 2, 14, "minecraft:tinted_glass");
        both(1, 1, 23, 6, 1, 23, "minecraft:cobblestone");
        both(1, 2, 23, 2, 2, 23, "minecraft:mossy_cobblestone");
    }

    /** Overgrown south end: moss and sculk floor, broken walls and glowing containment tanks. */
    private void infectedZone() {
        Random random = new Random(7);
        String[] ground = {"minecraft:moss_block", "minecraft:sculk", "minecraft:mossy_cobblestone", "minecraft:polished_andesite"};
        for (int x = 1; x < MAX; x++) {
            for (int z = 30; z < MAX; z++) {
                // denser towards the back wall
                if (random.nextInt(10) < z - 27) b.set(x, 0, z, ground[random.nextInt(ground.length)]);
            }
        }
        b.fill(15, 0, 33, 25, 0, 37, "minecraft:sculk");
        String ruin = "minecraft:cracked_deepslate_bricks";
        b.fill(4, 1, 32, 8, 2, 32, ruin);
        b.fill(5, 3, 32, 6, 3, 32, ruin);
        b.fill(32, 1, 33, 36, 3, 33, ruin);
        b.fill(12, 1, 31, 12, 2, 32, ruin);
        b.fill(28, 1, 37, 28, 2, 38, ruin);
        for (int x0 : new int[]{8, 30}) {
            b.fill(x0, 1, 35, x0 + 2, 3, 37, "minecraft:lime_stained_glass");
            b.fill(x0 + 1, 1, 36, x0 + 1, 3, 36, LIGHT);
            b.fill(x0, 4, 35, x0 + 2, 4, 37, "minecraft:polished_deepslate");
        }
    }

    /** Fills a box and its copy rotated 180° around the centre (stairs are turned around too). */
    private void both(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        b.fill(x1, y1, z1, x2, y2, z2, state);
        b.fill(MAX - x1, y1, MAX - z1, MAX - x2, y2, MAX - z2, turned(state));
    }

    private static String turned(String state) {
        if (state.contains("facing=east")) return state.replace("facing=east", "facing=west");
        if (state.contains("facing=west")) return state.replace("facing=west", "facing=east");
        if (state.contains("facing=north")) return state.replace("facing=north", "facing=south");
        if (state.contains("facing=south")) return state.replace("facing=south", "facing=north");
        return state;
    }
}
