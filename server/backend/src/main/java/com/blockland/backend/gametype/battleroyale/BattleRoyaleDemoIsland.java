package com.blockland.backend.gametype.battleroyale;

import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generated demo 大逃杀 map: a round island in a ring of shallow sea — rolling grass hills with a sand beach, stone
 * outcrops, a scatter of oak trees, a village of small plank houses, cobblestone ruins and a watchtower on the highest
 * hill. Loot spots lie on the house floors, in the ruins, on the tower and out in the open. Deterministic (fixed seed),
 * so a re-seeded backend produces the same file.
 */
public final class BattleRoyaleDemoIsland {
    public static final int SIZE_X = 160;
    public static final int SIZE_Y = 40;
    public static final int SIZE_Z = 160;
    /** Top of the sea (the water fills y 1 to this). */
    static final int SEA = 3;
    private static final double ISLAND_RADIUS = 62;
    private static final double SEA_RADIUS = 78;

    public record Result(byte[] data, List<BattleRoyaleMapSettings.Spot> lootSpots) {
    }

    private final StructureBuilder b = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);
    private final int[][] height = new int[SIZE_X][SIZE_Z];
    /** Columns of the island and its sea (the rest of the square is void). */
    private final boolean[][] sea = new boolean[SIZE_X][SIZE_Z];
    /** Columns something was built on (no trees or open-air loot there). */
    private final Set<Long> taken = new HashSet<>();
    private final List<BattleRoyaleMapSettings.Spot> spots = new ArrayList<>();
    private final Random random = new Random(20261024L);
    private final double[] noiseSeeds = {random.nextDouble() * 1000, random.nextDouble() * 1000, random.nextDouble() * 1000};

    private BattleRoyaleDemoIsland() {
    }

    public static Result generate() {
        BattleRoyaleDemoIsland island = new BattleRoyaleDemoIsland();
        island.terrain();
        island.village();
        island.ruins();
        island.tower();
        island.trees();
        island.openLoot(25);
        return new Result(island.b.build(), List.copyOf(island.spots));
    }

    // ---- terrain ---------------------------------------------------------------------------------------

    private void terrain() {
        double cx = SIZE_X / 2.0, cz = SIZE_Z / 2.0;
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                // a wobbly coastline, hills that fall off towards the shore
                double coast = ISLAND_RADIUS + 7 * fbm(x * 0.03, z * 0.03, 0);
                double t = 1 - Math.min(1, Math.max(0, d / coast));
                double hills = 4 + 9 * fbm(x * 0.045, z * 0.045, 1) + 4 * fbm(x * 0.11, z * 0.11, 2);
                int h = d < coast ? (int) Math.round(SEA - 1 + Math.pow(t, 0.6) * Math.max(2, hills)) : SEA - 2;
                h = Math.max(0, Math.min(SIZE_Y - 14, h));
                height[x][z] = h;
                // the sea ends two blocks inside the map's side, leaving room for the barrier that holds it in
                if (d > Math.min(SEA_RADIUS + 3 * fbm(x * 0.05, z * 0.05, 0), SIZE_X / 2.0 - 2)) continue;
                sea[x][z] = true;
                for (int y = 0; y <= h; y++) b.set(x, y, z, block(y, h, x, z));
                for (int y = h + 1; y <= SEA; y++) b.set(x, y, z, "minecraft:water");
            }
        }
        // an invisible wall round the sea: in the arena its edge would otherwise pour away into the void
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                if (sea[x][z]) continue;
                boolean shore = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (nx >= 0 && nz >= 0 && nx < SIZE_X && nz < SIZE_Z && sea[nx][nz]) shore = true;
                }
                if (shore) b.fill(x, 0, z, x, SEA, z, "minecraft:barrier");
            }
        }
    }

    private String block(int y, int top, int x, int z) {
        // the bottom layer is sandstone: sand on it stays put, over the void it would fall
        if (top <= SEA + 1) return y >= top - 2 && y > 0 ? "minecraft:sand" : "minecraft:sandstone";
        if (y == top) {
            // rocky tops on the highest hills
            if (top >= SEA + 12 && fbm(x * 0.2, z * 0.2, 2) > 0.2) return "minecraft:stone";
            return "minecraft:grass_block";
        }
        return y >= top - 3 ? "minecraft:dirt" : "minecraft:stone";
    }

    /** Smooth noise in about −1..1 (value noise, three octaves). */
    private double fbm(double x, double z, int channel) {
        double sum = 0, amp = 1, norm = 0, s = noiseSeeds[channel];
        for (int o = 0; o < 3; o++) {
            sum += amp * valueNoise(x + s, z - s);
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            z *= 2.03;
        }
        return sum / norm;
    }

    private static double valueNoise(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        double u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
        return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v;
    }

    private static double hash(int x, int z) {
        long h = x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 32767.5 - 1;
    }

    // ---- buildings -------------------------------------------------------------------------------------

    /** Six houses around the village square (south-west of the centre) and two on the eastern slopes. */
    private void village() {
        int[][] houses = {{48, 52}, {60, 46}, {44, 66}, {62, 64}, {52, 80}, {70, 78}, {104, 58}, {98, 96}};
        for (int[] h : houses) house(h[0], h[1]);
    }

    /** A 7 × 7 plank house with a cobblestone floor, a door on the south side, windows and a slab roof; 3 loot spots. */
    private void house(int x0, int z0) {
        int floor = levelArea(x0, z0, 7, 7);
        int x1 = x0 + 6, z1 = z0 + 6;
        b.fill(x0, floor, z0, x1, floor, z1, "minecraft:cobblestone");
        b.fill(x0, floor + 1, z0, x1, floor + 4, z1, "minecraft:air");
        for (int y = floor + 1; y <= floor + 3; y++) {
            b.fill(x0, y, z0, x1, y, z0, "minecraft:oak_planks");
            b.fill(x0, y, z1, x1, y, z1, "minecraft:oak_planks");
            b.fill(x0, y, z0, x0, y, z1, "minecraft:oak_planks");
            b.fill(x1, y, z0, x1, y, z1, "minecraft:oak_planks");
        }
        for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) b.fill(c[0], floor + 1, c[1], c[0], floor + 3, c[1], "minecraft:oak_log");
        // door gap (south) and windows (east, west, north)
        b.fill(x0 + 3, floor + 1, z1, x0 + 3, floor + 2, z1, "minecraft:air");
        b.set(x0, floor + 2, z0 + 3, "minecraft:glass_pane[east=true,west=true]");
        b.set(x1, floor + 2, z0 + 3, "minecraft:glass_pane[east=true,west=true]");
        b.set(x0 + 3, floor + 2, z0, "minecraft:glass_pane[north=true,south=true]");
        b.fill(x0 - 1, floor + 4, z0 - 1, x1 + 1, floor + 4, z1 + 1, "minecraft:spruce_slab[type=bottom]");
        b.set(x0 + 1, floor + 1, z0 + 1, "minecraft:crafting_table");
        b.set(x1 - 1, floor + 1, z0 + 1, "minecraft:barrel[facing=up]");
        spot(x0 + 2, floor + 1, z0 + 3);
        spot(x0 + 4, floor + 1, z0 + 2);
        spot(x0 + 4, floor + 1, z0 + 4);
        markTaken(x0 - 1, z0 - 1, 9, 9);
    }

    /** Broken cobblestone walls with moss and a loot spot or two inside. */
    private void ruins() {
        int[][] ruins = {{86, 34}, {30, 96}, {84, 112}, {118, 82}};
        for (int[] r : ruins) {
            int x0 = r[0], z0 = r[1];
            int floor = levelArea(x0, z0, 6, 6);
            for (int i = 0; i < 6; i++) {
                int wallHeight = 1 + random.nextInt(3);
                for (int y = 1; y <= wallHeight; y++) {
                    String stone = random.nextBoolean() ? "minecraft:cobblestone" : "minecraft:mossy_cobblestone";
                    b.set(x0 + i, floor + y, z0, stone);
                    if (i != 2 && i != 3) b.set(x0, floor + y, z0 + i, stone);
                    if (i % 2 == 0) b.set(x0 + 5, floor + y, z0 + i, stone);
                }
            }
            spot(x0 + 2, floor + 1, z0 + 2);
            spot(x0 + 3, floor + 1, z0 + 4);
            markTaken(x0 - 1, z0 - 1, 8, 8);
        }
    }

    /** A stone-brick watchtower on the highest hill, a ladder up and loot at the foot and on the platform. */
    private void tower() {
        int bestX = SIZE_X / 2, bestZ = SIZE_Z / 2;
        for (int x = 40; x < 120; x++) {
            for (int z = 40; z < 120; z++) {
                if (height[x][z] > height[bestX][bestZ] && !isTaken(x, z)) {
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        int x0 = bestX - 2, z0 = bestZ - 2;
        int floor = levelArea(x0, z0, 5, 5);
        int top = Math.min(SIZE_Y - 4, floor + 10);
        for (int y = floor + 1; y < top; y++) {
            for (int[] c : new int[][]{{x0, z0}, {x0 + 4, z0}, {x0, z0 + 4}, {x0 + 4, z0 + 4}}) b.set(c[0], y, c[1], "minecraft:stone_bricks");
        }
        b.fill(x0 - 1, top, z0 - 1, x0 + 5, top, z0 + 5, "minecraft:spruce_planks");
        b.fill(x0 + 2, top, z0 + 2, x0 + 2, top, z0 + 2, "minecraft:air");
        for (int y = floor + 1; y <= top; y++) b.set(x0 + 2, y, z0 + 2, "minecraft:ladder[facing=south]");
        // the ladder's wall: a pillar on its north side
        b.fill(x0 + 2, floor + 1, z0 + 1, x0 + 2, top - 1, z0 + 1, "minecraft:stone_bricks");
        for (int i = -1; i <= 5; i++) {
            for (int[] e : new int[][]{{x0 + i, z0 - 1}, {x0 + i, z0 + 5}, {x0 - 1, z0 + i}, {x0 + 5, z0 + i}}) {
                b.set(e[0], top + 1, e[1], "minecraft:spruce_fence");
            }
        }
        spot(x0 + 1, top + 1, z0 + 1);
        spot(x0 + 3, top + 1, z0 + 3);
        spot(x0 + 1, floor + 1, z0 + 3);
        markTaken(x0 - 2, z0 - 2, 9, 9);
    }

    private void trees() {
        int planted = 0;
        for (int attempt = 0; attempt < 600 && planted < 34; attempt++) {
            int x = 6 + random.nextInt(SIZE_X - 12), z = 6 + random.nextInt(SIZE_Z - 12);
            int ground = height[x][z];
            if (ground <= SEA + 1 || isTaken(x, z) || !"minecraft:grass_block".equals(block(ground, ground, x, z))) continue;
            int trunk = 4 + random.nextInt(2);
            for (int y = 1; y <= trunk; y++) b.set(x, ground + y, z, "minecraft:oak_log");
            int crown = ground + trunk;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (Math.abs(dx) + Math.abs(dz) + Math.max(0, dy) * 2 > 3 || (dx == 0 && dz == 0 && dy < 1)) continue;
                        b.set(x + dx, crown + dy, z + dz, "minecraft:oak_leaves[persistent=true]");
                    }
                }
            }
            b.set(x, crown + 1, z, "minecraft:oak_leaves[persistent=true]");
            markTaken(x - 2, z - 2, 5, 5);
            planted++;
        }
    }

    /** Loot lying out in the open on dry land, away from everything else. */
    private void openLoot(int count) {
        int placed = 0;
        for (int attempt = 0; attempt < 2000 && placed < count; attempt++) {
            int x = 8 + random.nextInt(SIZE_X - 16), z = 8 + random.nextInt(SIZE_Z - 16);
            int ground = height[x][z];
            if (ground <= SEA || isTaken(x, z)) continue;
            spot(x, ground + 1, z);
            markTaken(x - 4, z - 4, 9, 9);
            placed++;
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** Flattens a w × d patch to its average height (fills below, clears above) and returns the floor's y. */
    private int levelArea(int x0, int z0, int w, int d) {
        int sum = 0, n = 0;
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                sum += height[x][z];
                n++;
            }
        }
        int floor = Math.max(SEA + 1, Math.round(sum / (float) n));
        for (int x = x0 - 1; x <= x0 + w; x++) {
            for (int z = z0 - 1; z <= z0 + d; z++) {
                if (x < 0 || z < 0 || x >= SIZE_X || z >= SIZE_Z) continue;
                for (int y = 1; y < floor; y++) b.set(x, y, z, "minecraft:dirt");
                b.set(x, floor, z, "minecraft:grass_block");
                b.fill(x, floor + 1, z, x, Math.max(floor + 1, height[x][z]), z, "minecraft:air");
                height[x][z] = floor;
            }
        }
        return floor;
    }

    private void spot(int x, int y, int z) {
        spots.add(new BattleRoyaleMapSettings.Spot(x, y, z));
    }

    private void markTaken(int x0, int z0, int w, int d) {
        for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) taken.add(((long) x << 32) | (z & 0xFFFFFFFFL));
    }

    private boolean isTaken(int x, int z) {
        return taken.contains(((long) x << 32) | (z & 0xFFFFFFFFL));
    }
}
