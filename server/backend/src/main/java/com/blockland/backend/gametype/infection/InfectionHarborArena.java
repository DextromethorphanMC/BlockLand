package com.blockland.backend.gametype.infection;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generated 枪战感染 map around a harbour lake, made to show the water and lighting of the client's render pipeline: a
 * deep lake with shallow sandy banks, seagrass and sea lanterns on its floor, a wooden pier with lanterns out over the
 * water, a glass tunnel crossing under the lake (refraction, the surface seen from below), a lighthouse with a glowing
 * lantern room (long shadows, emissive light), a quay with iron, gold and copper blocks under a crane (metal highlights),
 * a glass greenhouse, oak trees (leaf shadows) and a blue-ice floe. Humans start on the north quay, monsters on the south
 * bank; two BOTH points at the pier head and on the east bank. Ground level: players stand on y 5.
 */
public final class InfectionHarborArena {
    public static final int SIZE_X = 49;
    public static final int SIZE_Y = 18;
    public static final int SIZE_Z = 49;
    private static final int MAX = SIZE_X - 1;
    /** Top of the ground; players stand on {@code SURFACE + 1}. */
    private static final int SURFACE = 4;
    /** Lake ellipse (centre and radii, in blocks). */
    private static final double LAKE_X = 24, LAKE_Z = 24, LAKE_RX = 14, LAKE_RZ = 10.5;

    private static final String WATER = "minecraft:water";
    private static final String PLANKS = "minecraft:spruce_planks";
    private static final String LANTERN = "minecraft:lantern[hanging=false,waterlogged=false]";
    private static final String LEAVES = "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]";

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns, List<InfectionMapSettings.Spawn> roleSpawns) {
    }

    private final StructureBuilder b = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);

    private InfectionHarborArena() {
    }

    public static Result generate() {
        return new InfectionHarborArena().build();
    }

    private Result build() {
        ground();
        lake();
        walls();
        pier();
        tunnel();
        lighthouse();
        quay();
        greenhouse();
        trees();

        List<GameMap.SpawnPoint> humans = new ArrayList<>();
        for (int z : new int[]{4, 6}) for (int x = 18; x <= 30; x += 3) humans.add(new GameMap.SpawnPoint(x, SURFACE + 1, z, 0f));
        List<GameMap.SpawnPoint> monsters = new ArrayList<>();
        for (int z : new int[]{42, 44}) for (int x = 18; x <= 30; x += 4) monsters.add(new GameMap.SpawnPoint(x, SURFACE + 1, z, 180f));
        List<GameMap.TeamSpawn> spawns = List.of(new GameMap.TeamSpawn("人类出生点", humans), new GameMap.TeamSpawn("怪物复活点", monsters));

        List<InfectionMapSettings.Spawn> roleSpawns = new ArrayList<>();
        for (GameMap.SpawnPoint p : humans) roleSpawns.add(new InfectionMapSettings.Spawn(p.x(), p.y(), p.z(), p.yaw(), InfectionMapSettings.Role.HUMAN));
        for (GameMap.SpawnPoint p : monsters) roleSpawns.add(new InfectionMapSettings.Spawn(p.x(), p.y(), p.z(), p.yaw(), InfectionMapSettings.Role.MONSTER));
        // the pier head looking back to the quay, and the east bank looking over the lake
        roleSpawns.add(new InfectionMapSettings.Spawn(24, SURFACE + 1, 21, 180f, InfectionMapSettings.Role.BOTH));
        roleSpawns.add(new InfectionMapSettings.Spawn(43, SURFACE + 1, 24, 90f, InfectionMapSettings.Role.BOTH));
        return new Result(b.build(), spawns, roleSpawns);
    }

    /** 0 inside the lake's centre, 1 on its edge, above 1 outside. */
    private static double lake(int x, int z) {
        double dx = (x - LAKE_X) / LAKE_RX, dz = (z - LAKE_Z) / LAKE_RZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void ground() {
        b.fill(0, 0, 0, MAX, 2, MAX, "minecraft:stone");
        b.fill(0, 3, 0, MAX, 3, MAX, "minecraft:dirt");
        b.fill(0, SURFACE, 0, MAX, SURFACE, MAX, "minecraft:grass_block[snowy=false]");
        // quay and camp in the north, a gravel road down the west side
        b.fill(1, SURFACE, 1, MAX - 1, SURFACE, 9, "minecraft:stone_bricks");
        b.fill(14, SURFACE, 2, 34, SURFACE, 8, "minecraft:polished_andesite");
        b.fill(2, SURFACE, 10, 4, SURFACE, MAX - 2, "minecraft:gravel");
        Random random = new Random(11);
        for (int x = 1; x < MAX; x++) {
            for (int z = 38; z < MAX; z++) {
                if (random.nextInt(6) == 0) b.set(x, SURFACE, z, "minecraft:coarse_dirt");
            }
        }
    }

    /** Deep in the middle (water y 1-4 over a sand and gravel floor), shallow towards a sandy bank. */
    private void lake() {
        Random random = new Random(5);
        for (int x = 1; x < MAX; x++) {
            for (int z = 10; z < 40; z++) {
                double d = lake(x, z);
                if (d > 1.25) continue;
                if (d > 1.0) {
                    b.set(x, SURFACE, z, "minecraft:sand");
                    continue;
                }
                int depth = d > 0.85 ? 1 : d > 0.7 ? 2 : 4;
                int floor = SURFACE - depth;
                b.fill(x, floor + 1, z, x, SURFACE, z, WATER);
                b.set(x, floor, z, random.nextInt(4) == 0 ? "minecraft:gravel" : "minecraft:sand");
                if (depth == 4 && random.nextInt(7) == 0) b.set(x, floor + 1, z, "minecraft:seagrass");
                if (depth == 4 && random.nextInt(40) == 0) b.set(x, floor, z, "minecraft:sea_lantern");
            }
        }
        // a blue-ice floe drifting in the east half
        b.fill(31, SURFACE, 19, 33, SURFACE, 21, "minecraft:blue_ice");
        b.set(34, SURFACE, 20, "minecraft:packed_ice");
    }

    private void walls() {
        String wall = "minecraft:stone_bricks";
        b.fill(0, SURFACE + 1, 0, MAX, SURFACE + 3, 0, wall);
        b.fill(0, SURFACE + 1, MAX, MAX, SURFACE + 3, MAX, wall);
        b.fill(0, SURFACE + 1, 0, 0, SURFACE + 3, MAX, wall);
        b.fill(MAX, SURFACE + 1, 0, MAX, SURFACE + 3, MAX, wall);
        for (int i = 6; i < MAX; i += 12) {
            b.set(i, SURFACE + 4, 0, LANTERN).set(i, SURFACE + 4, MAX, LANTERN).set(0, SURFACE + 4, i, LANTERN).set(MAX, SURFACE + 4, i, LANTERN);
        }
    }

    /** A plank pier from the quay out over the deep water, fence posts with lanterns, a small platform at its head. */
    private void pier() {
        b.fill(23, SURFACE, 10, 25, SURFACE, 20, PLANKS);
        b.fill(21, SURFACE, 20, 27, SURFACE, 23, PLANKS);
        // lanterns on fence posts along both edges of the walkway and the head platform
        for (int z = 11; z <= 23; z += 3) {
            int west = z <= 19 ? 23 : 21, east = z <= 19 ? 25 : 27;
            for (int x : new int[]{west, east}) {
                b.set(x, SURFACE + 1, z, "minecraft:spruce_fence");
                b.set(x, SURFACE + 2, z, LANTERN);
            }
        }
        // the piles down to the lake floor
        for (int[] p : new int[][]{{23, 12}, {25, 12}, {23, 16}, {25, 16}, {21, 21}, {27, 21}, {21, 23}, {27, 23}}) {
            b.fill(p[0], 0, p[1], p[0], SURFACE - 1, p[1], "minecraft:spruce_log[axis=y]");
        }
        b.set(24, SURFACE + 1, 23, "minecraft:barrel[facing=up,open=false]");
    }

    /**
     * A glass tunnel under the lake from the west bank to the east bank (inside y 1-2, glass roof on y 3, the lake's water
     * above), lit by sea lanterns in its floor, with a stairwell down at each end.
     */
    private void tunnel() {
        int z0 = 29, z1 = 31;
        for (int x = 8; x <= 40; x++) {
            b.fill(x, 0, z0 - 1, x, 3, z1 + 1, "minecraft:glass");
            b.fill(x, 0, z0, x, 0, z1, x % 4 == 0 ? "minecraft:sea_lantern" : "minecraft:prismarine_bricks");
            b.fill(x, 1, z0, x, 2, z1, "minecraft:air");
        }
        // stairwells: down from the bank (y 5) to the tunnel floor (y 1), open to the sky
        for (int i = 0; i < 4; i++) {
            int westX = 8 - 4 + i, eastX = 40 + 4 - i;
            b.fill(westX, SURFACE - i, z0, westX, SURFACE + 2, z1, "minecraft:air");
            b.fill(westX, SURFACE - i, z0, westX, SURFACE - i, z1, "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
            b.fill(eastX, SURFACE - i, z0, eastX, SURFACE + 2, z1, "minecraft:air");
            b.fill(eastX, SURFACE - i, z0, eastX, SURFACE - i, z1, "minecraft:stone_brick_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
        }
        // the stairwells' side walls keep the earth (and nobody) from falling in
        b.fill(4, SURFACE + 1, z0 - 1, 7, SURFACE + 1, z0 - 1, "minecraft:stone_brick_wall");
        b.fill(4, SURFACE + 1, z1 + 1, 7, SURFACE + 1, z1 + 1, "minecraft:stone_brick_wall");
        b.fill(41, SURFACE + 1, z0 - 1, 44, SURFACE + 1, z0 - 1, "minecraft:stone_brick_wall");
        b.fill(41, SURFACE + 1, z1 + 1, 44, SURFACE + 1, z1 + 1, "minecraft:stone_brick_wall");
    }

    /** A striped tower on the south-east bank with a glass lantern room (glowstone) and a walkway at the top. */
    private void lighthouse() {
        int x0 = 38, z0 = 37, x1 = 42, z1 = 41;
        for (int y = SURFACE + 1; y <= 13; y++) {
            String block = (y / 2) % 2 == 0 ? "minecraft:white_concrete" : "minecraft:red_concrete";
            b.fill(x0, y, z0, x1, y, z1, block);
            b.fill(x0 + 1, y, z0 + 1, x1 - 1, y, z1 - 1, "minecraft:air");
        }
        // a door on the north side and a ladder up the inside
        b.fill(40, SURFACE + 1, z0, 40, SURFACE + 2, z0, "minecraft:air");
        for (int y = SURFACE + 1; y <= 13; y++) b.set(40, y, 40, "minecraft:ladder[facing=north,waterlogged=false]");
        b.fill(x0 - 1, 14, z0 - 1, x1 + 1, 14, z1 + 1, "minecraft:polished_andesite");
        b.fill(x0 - 1, 15, z0 - 1, x1 + 1, 15, z1 + 1, "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]");
        b.fill(x0, 15, z0, x1, 16, z1, "minecraft:glass");
        b.fill(x0 + 1, 15, z0 + 1, x1 - 1, 16, z1 - 1, "minecraft:glowstone");
        b.set(40, 14, 40, "minecraft:air");
        b.fill(x0, 17, z0, x1, 17, z1, "minecraft:red_concrete");
    }

    /** The quay's east end: a warehouse wall, metal blocks under an iron crane with a chain. */
    private void quay() {
        b.fill(37, SURFACE + 1, 2, 46, SURFACE + 4, 3, "minecraft:bricks");
        b.fill(39, SURFACE + 1, 3, 40, SURFACE + 2, 3, "minecraft:air");
        b.fill(36, SURFACE + 1, 6, 37, SURFACE + 2, 7, "minecraft:iron_block");
        b.fill(39, SURFACE + 1, 6, 40, SURFACE + 1, 7, "minecraft:gold_block");
        b.fill(42, SURFACE + 1, 6, 43, SURFACE + 2, 7, "minecraft:waxed_copper_block");
        b.set(42, SURFACE + 3, 6, "minecraft:waxed_exposed_copper");
        b.fill(45, SURFACE + 1, 6, 45, SURFACE + 9, 6, "minecraft:iron_block");
        b.fill(36, SURFACE + 9, 6, 45, SURFACE + 9, 6, "minecraft:iron_block");
        b.fill(38, SURFACE + 5, 6, 38, SURFACE + 8, 6, "minecraft:chain[axis=y,waterlogged=false]");
        b.set(38, SURFACE + 4, 6, "minecraft:lantern[hanging=true,waterlogged=false]");
        // bollards and a gold-trimmed edge along the water
        for (int x = 14; x <= 34; x += 5) b.set(x, SURFACE + 1, 9, "minecraft:polished_deepslate_wall");
    }

    /** A clear and stained glass greenhouse on the south-west bank, flowers and a lantern inside. */
    private void greenhouse() {
        int x0 = 6, z0 = 37, x1 = 13, z1 = 44;
        b.fill(x0, SURFACE + 1, z0, x1, SURFACE + 4, z1, "minecraft:glass");
        b.fill(x0 + 1, SURFACE + 1, z0 + 1, x1 - 1, SURFACE + 3, z1 - 1, "minecraft:air");
        b.fill(x0, SURFACE + 5, z0, x1, SURFACE + 5, z1, "minecraft:light_blue_stained_glass");
        b.fill(x0 + 2, SURFACE + 5, z0 + 2, x1 - 2, SURFACE + 5, z1 - 2, "minecraft:lime_stained_glass");
        b.fill(x0 + 3, SURFACE + 1, z0, x0 + 4, SURFACE + 2, z0, "minecraft:air");
        b.fill(x0 + 1, SURFACE, z0 + 1, x1 - 1, SURFACE, z1 - 1, "minecraft:moss_block");
        String[] flowers = {"minecraft:poppy", "minecraft:dandelion", "minecraft:blue_orchid", "minecraft:allium", "minecraft:cornflower"};
        Random random = new Random(3);
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 2; z < z1; z++) if (random.nextInt(3) == 0) b.set(x, SURFACE + 1, z, flowers[random.nextInt(flowers.length)]);
        }
        b.set(x0 + 4, SURFACE + 3, z0 + 4, "minecraft:lantern[hanging=true,waterlogged=false]");
    }

    /** Oak trees along the west bank and by the camp (persistent leaves: they never decay). */
    private void trees() {
        int[][] at = {{7, 14}, {6, 22}, {4, 34}, {12, 6}, {44, 14}, {44, 33}, {33, 44}};
        for (int[] t : at) {
            int x = t[0], z = t[1], top = SURFACE + 5;
            b.fill(x - 2, top - 1, z - 2, x + 2, top, z + 2, LEAVES);
            b.fill(x - 1, top + 1, z - 1, x + 1, top + 1, z + 1, LEAVES);
            b.set(x, top + 2, z, LEAVES);
            b.fill(x, SURFACE + 1, z, x, top, z, "minecraft:oak_log[axis=y]");
        }
    }
}
