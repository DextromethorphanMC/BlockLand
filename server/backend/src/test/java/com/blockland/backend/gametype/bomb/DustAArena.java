package com.blockland.backend.gametype.bomb;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * DustA (沙漠小镇), a generated desert town for 枪战爆破 — an original layout, 128 × 112 blocks. Attackers start in the
 * caravan yard in the south, defenders on the garrison terrace in the north. Site A is a raised courtyard in the north
 * west, site B a market in a dry riverbed (the wadi) in the north east, mid a long covered bazaar between them. Each
 * site has three ways in for the attackers — A: Well Street up the ramp, the Dye Roof above it, the Tea House from mid;
 * B: Gate Street down the ramp, the Culvert along the riverbed, the Spice Steps from mid — and the defenders have one
 * from their terrace to each site (the Tower Walk, the Stable Lane, with a ladder up to the Lantern Roof over B) and
 * the Clock Arch down into mid. The bazaar meets the yard only through a dog-leg, so neither spawn sees the other.
 *
 * <p>The map is drawn as a raster: every column is a walking level (street, raised, wadi) or a building; buildings fill
 * everything that is not walked on, as walls along the streets and a roof over the rest, so there are no gaps in the
 * town. Two roofs are meant to be stood on (the Dye Roof, the Lantern Roof); every other roof is at least two blocks
 * above anything one can stand next to it, and their edges hold walls one cannot jump.
 */
public final class DustAArena {
    public static final int SIZE_X = 128;
    public static final int SIZE_Y = 21;
    /** The town stands on a layer of sandstone: in the arena the sand under it would fall into the void. */
    static final int LIFT = 1;
    public static final int SIZE_Z = 112;

    /** What a column is: built up, or a floor at one of the walking levels. */
    static final int BUILDING = 0, STREET = 1, RAISED = 2, WADI = 3;
    /** Feet heights of the levels (the floor block is one below). */
    static final int FEET_STREET = 3, FEET_RAISED = 5, FEET_WADI = 1;
    /** The roof block of the roofs one stands on (feet one above). */
    static final int ROOF_LOW = 8;
    static final int ROOF_DEFAULT = 10;

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns, List<BombMapSettings.Site> sites, StructureBuilder blocks) {
    }

    private final StructureBuilder base = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);
    /** Where the town is drawn: the y of everything below is one above the foundation's. */
    private final StructureBuilder b = base.lifted(LIFT);
    private final int[][] kind = new int[SIZE_X][SIZE_Z];
    private final int[][] roof = new int[SIZE_X][SIZE_Z];
    private final String[][] wall = new String[SIZE_X][SIZE_Z];
    private final boolean[][] walkableRoof = new boolean[SIZE_X][SIZE_Z];
    private final boolean[][] special = new boolean[SIZE_X][SIZE_Z];

    private DustAArena() {
    }

    public static Result generate() {
        return new DustAArena().build();
    }

    private Result build() {
        base.fill(0, 0, 0, SIZE_X - 1, LIFT - 1, SIZE_Z - 1, "minecraft:sandstone");
        plan();
        ground();
        buildings();
        stairs();
        covers();
        decorate();
        List<BombMapSettings.Site> sites = List.of(
                new BombMapSettings.Site("A", 11, LIFT + FEET_RAISED, 23, 24, LIFT + FEET_RAISED + 2, 34),
                new BombMapSettings.Site("B", 100, LIFT + FEET_WADI, 25, 113, LIFT + FEET_WADI + 2, 36));
        List<GameMap.SpawnPoint> attackers = new ArrayList<>(), defenders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // yaw 0 faces +z (south), 180 faces -z (north)
            attackers.add(new GameMap.SpawnPoint(58 + i * 3, LIFT + FEET_STREET, 105, 180f));
            defenders.add(new GameMap.SpawnPoint(58 + i * 3, LIFT + FEET_RAISED, 8, 0f));
        }
        List<GameMap.TeamSpawn> spawns = List.of(new GameMap.TeamSpawn("进攻方", attackers), new GameMap.TeamSpawn("防守方", defenders));
        return new Result(base.build(), spawns, sites, base);
    }

    // ---- the plan ---------------------------------------------------------------------------------

    private void plan() {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                roof[x][z] = ROOF_DEFAULT;
                wall[x][z] = "minecraft:sandstone";
            }
        }
        // building lots (later ones win): height and walls
        lot(0, 0, 33, 16, 12, "minecraft:smooth_sandstone");          // the Tower block behind A
        lot(2, 2, 7, 7, 15, "minecraft:cut_sandstone");              // the tower itself
        lot(0, 46, 11, 111, 10, "minecraft:white_terracotta");        // houses west of Well Street
        lot(22, 63, 43, 93, 10, "minecraft:terracotta");              // south of the Dye House
        lot(40, 46, 55, 93, 11, "minecraft:smooth_sandstone");        // between the Dye House and the bazaar
        lot(40, 17, 55, 39, 10, "minecraft:sandstone");               // east of the courtyard
        lot(34, 0, 127, 3, 11, "minecraft:smooth_sandstone");         // the garrison's back wall
        lot(72, 17, 85, 37, 10, "minecraft:white_terracotta");        // the Spice House
        lot(72, 46, 91, 89, 10, "minecraft:orange_terracotta");       // the Bath House
        lot(98, 48, 107, 93, 10, "minecraft:sandstone");              // between the Culvert and Gate Street
        lot(118, 48, 127, 111, 10, "minecraft:red_sandstone");        // the east wall
        lot(122, 0, 127, 47, 10, "minecraft:smooth_red_sandstone");   // east of the wadi
        lot(44, 76, 55, 91, 12, "minecraft:cut_sandstone");           // the Clock House
        lot(46, 82, 51, 87, 15, "minecraft:chiseled_sandstone");      // its clock tower
        // the two roofs one stands on
        lot(22, 46, 39, 62, ROOF_LOW, "minecraft:orange_terracotta"); // the Dye House (A)
        lot(100, 4, 113, 17, ROOF_LOW, "minecraft:light_gray_terracotta"); // the Lantern House (B)
        roofTop(22, 46, 39, 57);
        roofTop(24, 58, 39, 62);
        roofTop(100, 4, 113, 17);

        // walking levels
        area(STREET, 44, 92, 85, 108);    // the caravan yard (attackers' spawn)
        area(STREET, 20, 94, 43, 101);    // to Well Street
        area(STREET, 12, 48, 21, 101);    // Well Street
        area(STREET, 22, 58, 23, 64);     // the Dye House stairs
        area(STREET, 56, 19, 71, 75);     // the bazaar (mid)
        area(STREET, 56, 76, 61, 83);     // the dog-leg from the bazaar ...
        area(STREET, 56, 84, 77, 89);
        area(STREET, 72, 90, 77, 91);     // ... into the yard
        area(STREET, 58, 17, 69, 18);     // the Clock Arch steps
        area(STREET, 41, 49, 55, 55);     // the Tea House passage
        area(STREET, 41, 47, 46, 48);     // and its steps up to the landing
        area(STREET, 72, 38, 85, 45);     // the Spice passage
        area(STREET, 86, 94, 117, 101);   // to the Old Gate
        area(STREET, 92, 87, 97, 93);     // the Culvert's head
        area(STREET, 108, 49, 117, 93);   // Gate Street
        area(STREET, 12, 46, 21, 47);     // the courtyard ramp
        area(RAISED, 8, 18, 39, 45);      // the courtyard (A)
        area(RAISED, 40, 40, 46, 46);     // the Tea House landing
        area(RAISED, 34, 9, 43, 14);      // the Tower Walk
        area(RAISED, 34, 15, 39, 17);
        area(RAISED, 44, 4, 83, 16);      // the garrison terrace (defenders' spawn)
        area(RAISED, 84, 8, 99, 13);      // the Stable Lane
        area(WADI, 86, 18, 121, 47);      // the wadi (B)
        area(WADI, 108, 48, 117, 48);
        area(WADI, 92, 48, 97, 86);       // the Culvert
        area(WADI, 92, 14, 97, 17);       // the stable steps (stairs, set below)
    }

    private void lot(int x1, int z1, int x2, int z2, int roofY, String material) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                roof[x][z] = roofY;
                wall[x][z] = material;
            }
        }
    }

    private void roofTop(int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) walkableRoof[x][z] = true;
    }

    private void area(int k, int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                kind[x][z] = k;
                walkableRoof[x][z] = false;
            }
        }
    }

    private boolean walkable(int x, int z) {
        return x >= 0 && z >= 0 && x < SIZE_X && z < SIZE_Z && kind[x][z] != BUILDING;
    }

    /** The feet height of a walking column. */
    private int feet(int x, int z) {
        return switch (kind[x][z]) {
            case RAISED -> FEET_RAISED;
            case WADI -> FEET_WADI;
            default -> FEET_STREET;
        };
    }

    // ---- floors -----------------------------------------------------------------------------------

    private void ground() {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                int h = hash(x, z);
                switch (kind[x][z]) {
                    case STREET -> {
                        b.fill(x, 0, z, x, 1, z, "minecraft:sandstone");
                        b.set(x, 2, z, streetFloor(x, z, h));
                    }
                    case RAISED -> {
                        b.fill(x, 0, z, x, 3, z, "minecraft:sandstone");
                        b.set(x, 4, z, raisedFloor(x, z, h));
                    }
                    case WADI -> b.set(x, 0, z, h % 37 == 0 ? "minecraft:gravel" : h % 13 == 0 ? "minecraft:red_sand" : "minecraft:sand");
                    default -> b.fill(x, 0, z, x, 1, z, "minecraft:sandstone");
                }
            }
        }
        // the sites' floors
        floor(11, 23, 24, 34, 4, (x, z) -> x == 11 || x == 24 || z == 23 || z == 34 ? "minecraft:cut_red_sandstone" : "minecraft:smooth_red_sandstone");
        floor(100, 25, 113, 36, 0, (x, z) -> x == 100 || x == 113 || z == 25 || z == 36 ? "minecraft:cut_red_sandstone" : "minecraft:red_sand");
    }

    private interface Pattern {
        String at(int x, int z);
    }

    private void floor(int x1, int z1, int x2, int z2, int y, Pattern p) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) b.set(x, y, z, p.at(x, z));
    }

    private static String streetFloor(int x, int z, int h) {
        // a paved lane down the bazaar, sand elsewhere with worn stone
        if (x >= 56 && x <= 71 && z >= 19 && z <= 75) return (x == 63 || x == 64) ? "minecraft:cut_sandstone"
                : h % 5 == 0 ? "minecraft:sand" : "minecraft:smooth_sandstone";
        if (h % 7 == 0) return "minecraft:smooth_sandstone";
        if (h % 23 == 0) return "minecraft:red_sand";
        return "minecraft:sand";
    }

    private static String raisedFloor(int x, int z, int h) {
        return ((x + z) & 1) == 0 ? "minecraft:cut_sandstone" : h % 11 == 0 ? "minecraft:chiseled_sandstone" : "minecraft:smooth_sandstone";
    }

    // ---- buildings --------------------------------------------------------------------------------

    private void buildings() {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                if (kind[x][z] != BUILDING) continue;
                int top = roof[x][z];
                boolean facade = false;
                int lowest = FEET_STREET;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (walkable(x + dx, z + dz)) {
                            facade = true;
                            lowest = Math.min(lowest, feet(x + dx, z + dz));
                        }
                        // a building next to a roof one stands on is solid there too (else one steps into it)
                        int nx = x + dx, nz = z + dz;
                        if (!walkableRoof[x][z] && nx >= 0 && nz >= 0 && nx < SIZE_X && nz < SIZE_Z && walkableRoof[nx][nz]) facade = true;
                    }
                }
                // the edge of the map is always a wall
                if (x == 0 || z == 0 || x == SIZE_X - 1 || z == SIZE_Z - 1) facade = true;
                if (facade) {
                    String material = wall[x][z];
                    for (int y = Math.min(2, lowest); y < top; y++) {
                        // a darker footing up to the window sills, a cornice under the roof
                        String m = y < lowest + 1 ? base(material) : y == top - 1 ? cornice(material) : material;
                        b.set(x, y, z, m);
                    }
                    b.set(x, top, z, roofBlock(material));
                    parapet(x, z, top);
                } else {
                    b.set(x, top, z, roofBlock(wall[x][z]));
                }
            }
        }
        windowsAndDoors();
    }

    /** A wall one cannot jump along a roof's edge over a drop; the walkable roofs' edges get gaps (crenels). */
    private void parapet(int x, int z, int top) {
        boolean dropX = walkable(x + 1, z) || walkable(x - 1, z), dropZ = walkable(x, z + 1) || walkable(x, z - 1);
        if (!dropX && !dropZ) return;
        if (walkableRoof[x][z]) {
            // open where one comes up: the Dye House stairs' top, the Lantern House ladder
            if (z == 57 && x <= 23 || x == 100 && z >= 10 && z <= 12) return;
            // crenels on the sides that look over a site, a closed wall elsewhere (a wall is too high to jump)
            boolean crenel = (z == 46 || z == 17) && (x + z) % 3 == 0;
            String sides = (dropZ ? "east=low,west=low" : "") + (dropX && dropZ ? "," : "") + (dropX ? "north=low,south=low" : "");
            if (!crenel) b.set(x, top + 1, z, "minecraft:sandstone_wall[up=true," + sides + "]");
        } else {
            b.set(x, top + 1, z, (x + z) % 4 == 0 ? "minecraft:chiseled_sandstone" : "minecraft:smooth_sandstone_slab[type=bottom]");
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static String base(String material) {
        return material.contains("terracotta") ? "minecraft:smooth_sandstone" : material.contains("red_sandstone") ? "minecraft:cut_red_sandstone"
                : "minecraft:cut_sandstone";
    }

    private static String cornice(String material) {
        return material.contains("red") ? "minecraft:chiseled_red_sandstone" : "minecraft:chiseled_sandstone";
    }

    private static String roofBlock(String material) {
        return material.contains("red") ? "minecraft:smooth_red_sandstone" : "minecraft:smooth_sandstone";
    }

    /**
     * Windows (dark glass, flush) along the walls over the streets, and a few doors with a block behind them (the
     * buildings are hollow: an opened door shows a wall, not a hiding place); cloth awnings over some doors.
     */
    private void windowsAndDoors() {
        for (int x = 1; x < SIZE_X - 1; x++) {
            for (int z = 1; z < SIZE_Z - 1; z++) {
                if (kind[x][z] != BUILDING || walkableRoof[x][z]) continue;
                int[] out = null;
                for (int[] d : DIRS) if (walkable(x + d[0], z + d[1]) && kind[x + d[0]][z + d[1]] != RAISED) out = d;
                if (out == null) continue;
                // only plain walls: not a corner (both sides walkable)
                if (walkable(x - out[0], z - out[1])) continue;
                int f = feet(x + out[0], z + out[1]);
                int along = out[0] != 0 ? z : x;
                int h = hash(x, z);
                if (roof[x][z] - f >= 5 && along % 5 == 2) {
                    b.set(x, f + 2, z, "minecraft:black_stained_glass");
                    if (roof[x][z] - f >= 7) b.set(x, f + 3, z, "minecraft:black_stained_glass");
                } else if (along % 11 == 6 && h % 3 == 0 && kind[x + out[0]][z + out[1]] == STREET) {
                    String facing = facing(out);
                    b.set(x, f, z, "minecraft:spruce_door[half=lower,facing=" + facing + ",hinge=left,open=false]");
                    b.set(x, f + 1, z, "minecraft:spruce_door[half=upper,facing=" + facing + ",hinge=left,open=false]");
                    b.fill(x - out[0], f, z - out[1], x - out[0], f + 1, z - out[1], "minecraft:sandstone");
                    if (!special[x + out[0]][z + out[1]]) b.set(x + out[0], f + 3, z + out[1], CLOTH[h % CLOTH.length]);
                }
            }
        }
    }

    private static final String[] CLOTH = {"minecraft:red_wool", "minecraft:orange_wool", "minecraft:white_wool", "minecraft:yellow_wool",
            "minecraft:cyan_wool"};

    private static String facing(int[] d) {
        if (d[0] > 0) return "east";
        if (d[0] < 0) return "west";
        return d[1] > 0 ? "south" : "north";
    }

    // ---- stairs, ramps, the ladder ----------------------------------------------------------------

    private void stairs() {
        // the Clock Arch: terrace (5) down to the bazaar (3), going south
        steps(58, 69, 17, 18, true, FEET_RAISED);
        // the courtyard ramp: Well Street (3) up to the courtyard (5), going north
        steps(12, 21, 46, 47, true, FEET_RAISED);
        // the Tea House steps: passage (3) up to the landing (5)
        steps(41, 46, 47, 48, true, FEET_RAISED);
        // the Dye House stairs: Well Street (3) up to the roof (9), along x 22..23 going north
        for (int i = 0; i <= 5; i++) {
            int z = 63 - i, y = FEET_STREET + i;
            for (int x = 22; x <= 23; x++) {
                b.fill(x, 0, z, x, y - 1, z, "minecraft:sandstone");
                b.set(x, y, z, "minecraft:sandstone_stairs[facing=north,half=bottom]");
                special[x][z] = true;
            }
        }
        // the Stable steps: lane (5) down to the wadi (1), going south
        for (int i = 0; i < 4; i++) {
            int z = 14 + i, y = FEET_RAISED - 2 - i;
            for (int x = 92; x <= 97; x++) {
                if (y > 0) b.fill(x, 0, z, x, y - 1, z, "minecraft:sandstone");
                b.set(x, y, z, "minecraft:smooth_sandstone_stairs[facing=north,half=bottom]");
                special[x][z] = true;
            }
        }
        // Gate Street (3) down to the wadi (1): one step at z 49
        for (int x = 108; x <= 117; x++) {
            b.set(x, 2, 49, "minecraft:air");
            b.set(x, 1, 49, "minecraft:sandstone_stairs[facing=south,half=bottom]");
            special[x][49] = true;
        }
        // the Spice Steps: passage (3) down into the wadi at x 86
        for (int z = 38; z <= 45; z++) {
            b.set(86, 1, z, "minecraft:sandstone_stairs[facing=west,half=bottom]");
            special[86][z] = true;
        }
        // the Culvert's head: one step down at z 87
        for (int x = 92; x <= 97; x++) {
            b.set(x, 2, 87, "minecraft:air");
            b.set(x, 1, 87, "minecraft:sandstone_stairs[facing=south,half=bottom]");
            special[x][87] = true;
        }
        // the Lantern Roof's ladder, from the Stable Lane
        for (int y = FEET_RAISED; y <= ROOF_LOW; y++) b.set(99, y, 11, "minecraft:ladder[facing=west]");
    }

    /** Two rows of stairs from a lower level (3) up to {@code top} (5), ascending towards {@code north} (low z) or south. */
    private void steps(int x1, int x2, int zLow, int zHigh, boolean north, int top) {
        int upper = north ? zLow : zHigh, lower = north ? zHigh : zLow;
        String facing = north ? "north" : "south";
        for (int x = x1; x <= x2; x++) {
            b.fill(x, 0, lower, x, 2, lower, "minecraft:sandstone");
            b.set(x, 3, lower, "minecraft:sandstone_stairs[facing=" + facing + ",half=bottom]");
            b.fill(x, 0, upper, x, 3, upper, "minecraft:sandstone");
            b.set(x, 4, upper, "minecraft:sandstone_stairs[facing=" + facing + ",half=bottom]");
            special[x][lower] = true;
            special[x][upper] = true;
        }
    }

    // ---- covered streets --------------------------------------------------------------------------

    private void covers() {
        // the courtyard's arcade along its north side: a roof on pillars
        for (int x = 8; x <= 33; x++) {
            for (int z = 18; z <= 20; z++) b.set(x, 9, z, z == 20 ? "minecraft:cut_sandstone" : "minecraft:smooth_sandstone");
            if (x % 4 == 0) b.fill(x, FEET_RAISED, 20, x, 8, 20, "minecraft:chiseled_sandstone");
            if (x % 4 == 2) b.set(x, 8, 19, "minecraft:lantern[hanging=true]");
        }
        // the Tea House: a ceiling over the passage, lanterns, arches at both ends
        for (int x = 41; x <= 55; x++) {
            for (int z = 49; z <= 55; z++) b.set(x, 8, z, "minecraft:smooth_sandstone");
            if (x % 5 == 1) b.set(x, 7, 52, "minecraft:lantern[hanging=true]");
        }
        arch(55, 49, 55, 55, FEET_STREET, 8, true);
        // the Old Gate: Gate Street covered from z 66 to 80, the gate arch in the middle
        for (int z = 66; z <= 80; z++) {
            for (int x = 108; x <= 117; x++) b.set(x, 8, z, "minecraft:smooth_sandstone");
            if (z % 4 == 2) b.set(112, 7, z, "minecraft:lantern[hanging=true]");
        }
        arch(108, 66, 117, 66, FEET_STREET, 8, false);
        arch(108, 80, 117, 80, FEET_STREET, 8, false);
        arch(108, 73, 117, 73, FEET_STREET, 8, false);
        // the Clock Arch over the terrace steps
        for (int x = 58; x <= 69; x++) b.fill(x, 9, 17, x, 10, 18, x % 3 == 0 ? "minecraft:chiseled_sandstone" : "minecraft:cut_sandstone");
        for (int z = 17; z <= 18; z++) {
            b.set(58, 8, z, "minecraft:sandstone_stairs[facing=east,half=top]");
            b.set(69, 8, z, "minecraft:sandstone_stairs[facing=west,half=top]");
        }
        // shade cloth over the bazaar, in strips with gaps (out of reach: two blocks under the roofs around it)
        for (int z = 22; z <= 74; z++) {
            if (z % 6 > 2 || (z >= 60 && z <= 69)) continue;
            String cloth = CLOTH[(z / 6) % CLOTH.length];
            for (int x = 56; x <= 71; x++) b.set(x, 9, z, (x + z / 6) % 7 == 3 ? "minecraft:white_wool" : cloth);
        }
        // and two over Well Street, away from the Dye House roof and its stairs
        for (int z : new int[]{78, 79, 86, 87}) for (int x = 12; x <= 21; x++) b.set(x, 9, z, z < 82 ? "minecraft:orange_wool" : "minecraft:white_wool");
    }

    /** An arch across a street (a row of blocks under a ceiling at {@code top}, with stairs turned over at its ends). */
    private void arch(int x1, int z1, int x2, int z2, int feet, int top, boolean alongZ) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) b.set(x, top - 1, z, "minecraft:cut_sandstone");
        if (alongZ) {
            b.set(x1, top - 2, z1, "minecraft:sandstone_stairs[facing=south,half=top]");
            b.set(x1, top - 2, z2, "minecraft:sandstone_stairs[facing=north,half=top]");
        } else {
            b.set(x1, top - 2, z1, "minecraft:sandstone_stairs[facing=east,half=top]");
            b.set(x2, top - 2, z1, "minecraft:sandstone_stairs[facing=west,half=top]");
        }
    }

    // ---- cover, landmarks, decoration -------------------------------------------------------------

    private void decorate() {
        // the caravan yard: crates, hay, barrels, palms
        crates(50, 97, 2, 2, FEET_STREET);
        crates(79, 96, 2, 2, FEET_STREET);
        crates(56, 99, 1, 1, FEET_STREET);
        crates(73, 101, 1, 2, FEET_STREET);
        hay(66, 96, FEET_STREET);
        hay(67, 96, FEET_STREET);
        barrels(45, 93, 3, FEET_STREET);
        palm(47, FEET_STREET, 106, 6);
        palm(83, FEET_STREET, 106, 7);
        palm(46, FEET_STREET, 93, 5);
        // Well Street: the well, crates, a palm
        well(15, 72);
        crates(13, 88, 1, 1, FEET_STREET);
        crates(20, 66, 1, 1, FEET_STREET);
        crates(19, 52, 2, 1, FEET_STREET);
        barrels(13, 56, 2, FEET_STREET);
        palm(20, FEET_STREET, 96, 6);
        // the courtyard (A): crates on the site, a fountain, planters, palms, the letter
        crates(14, 26, 2, 2, FEET_RAISED);
        crates(19, 31, 1, 1, FEET_RAISED);
        crates(22, 24, 1, 1, FEET_RAISED);
        crates(12, 32, 1, 1, FEET_RAISED);
        barrels(23, 33, 2, FEET_RAISED);
        fountain(27, 30, FEET_RAISED, 6);
        planter(31, 40, FEET_RAISED);
        planter(16, 40, FEET_RAISED);
        crates(36, 24, 2, 1, FEET_RAISED);
        palm(37, FEET_RAISED, 43, 6);
        palm(9, FEET_RAISED, 44, 5);
        letter(LETTER_A, 7, 27, FEET_RAISED, true, "minecraft:red_terracotta");
        // the Tea House landing and passage
        barrels(44, 54, 2, FEET_STREET);
        crates(51, 50, 1, 1, FEET_STREET);
        crates(43, 41, 1, 1, FEET_RAISED);
        // the garrison terrace (defenders) and the Tower Walk
        crates(50, 13, 2, 2, FEET_RAISED);
        crates(76, 6, 2, 1, FEET_RAISED);
        crates(38, 12, 1, 1, FEET_RAISED);
        barrels(80, 13, 2, FEET_RAISED);
        palm(46, FEET_RAISED, 6, 6);
        palm(81, FEET_RAISED, 15, 5);
        // the bazaar (mid): stalls along the sides, crates that break the long view, the fountain
        stall(57, 26, 3, true);
        stall(70, 34, 3, false);
        stall(57, 44, 3, true);
        stall(70, 56, 3, false);
        crates(63, 33, 2, 2, FEET_STREET);
        crates(60, 47, 2, 2, FEET_STREET);
        crates(67, 72, 1, 1, FEET_STREET);
        fountain(61, 62, FEET_STREET, 6);
        palm(57, FEET_STREET, 74, 5);
        // the dog-leg and the Spice passage
        crates(58, 87, 1, 1, FEET_STREET);
        crates(78, 41, 1, 2, FEET_STREET);
        hay(83, 39, FEET_STREET);
        // the wadi (B): crates on the site, a stall, boulders, a low wall, dead bushes, palms, the letter
        crates(104, 28, 2, 2, FEET_WADI);
        crates(110, 33, 1, 1, FEET_WADI);
        crates(101, 34, 1, 1, FEET_WADI);
        crates(112, 26, 1, 1, FEET_WADI);
        stall(96, 27, 3, true);
        boulder(90, 40);
        boulder(116, 41);
        boulder(118, 22);
        b.fill(100, FEET_WADI, 42, 106, FEET_WADI, 42, "minecraft:sandstone");
        crates(88, 30, 1, 1, FEET_WADI);
        palm(89, FEET_WADI, 20, 7);
        palm(120, FEET_WADI, 46, 6);
        letter(LETTER_B, 122, 29, FEET_WADI + 1, false, "minecraft:blue_terracotta");
        // the Culvert: a footbridge high over it, a few crates
        for (int x = 92; x <= 97; x++) b.set(x, 6, 66, x == 92 || x == 97 ? "minecraft:sandstone_stairs[facing=" + (x == 92 ? "east" : "west") + ",half=top]"
                : "minecraft:cut_sandstone");
        crates(93, 70, 1, 1, FEET_WADI);
        crates(96, 58, 1, 1, FEET_WADI);
        crates(94, 79, 1, 1, FEET_WADI);
        // Gate Street and the Old Gate passage
        crates(116, 55, 1, 2, FEET_STREET);
        crates(109, 86, 2, 1, FEET_STREET);
        barrels(117, 90, 2, FEET_STREET);
        hay(100, 99, FEET_STREET);
        palm(116, FEET_STREET, 100, 6);
        // the Stable Lane
        hay(88, 9, FEET_RAISED);
        hay(89, 9, FEET_RAISED);
        // dead bushes on bare sand
        for (int x = 1; x < SIZE_X - 1; x++) {
            for (int z = 1; z < SIZE_Z - 1; z++) {
                if (special[x][z] || hash(x * 3 + 7, z * 5 + 1) % 41 != 0) continue;
                int y = kind[x][z] == WADI ? FEET_WADI : kind[x][z] == STREET ? FEET_STREET : -1;
                if (y < 0 || b.get(x, y, z) != null) continue;
                String under = b.get(x, y - 1, z);
                if ("minecraft:sand".equals(under) || "minecraft:red_sand".equals(under)) b.set(x, y, z, "minecraft:dead_bush");
            }
        }
    }

    /** Wooden crates: {@code w} × {@code w} across, {@code h} high. */
    private void crates(int x, int z, int w, int h, int feet) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < w; dz++) {
                for (int dy = 0; dy < h; dy++) {
                    b.set(x + dx, feet + dy, z + dz, (dx + dz + dy) % 2 == 0 ? "minecraft:spruce_planks" : "minecraft:stripped_spruce_wood[axis=y]");
                }
            }
        }
    }

    private void barrels(int x, int z, int n, int feet) {
        for (int i = 0; i < n; i++) b.set(x + i, feet, z, "minecraft:barrel[facing=up,open=false]");
    }

    private void hay(int x, int z, int feet) {
        b.set(x, feet, z, "minecraft:hay_block[axis=x]");
    }

    /** A low ring of stone with water in it, a roof on four posts (out of reach). */
    private void well(int x, int z) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                boolean rim = dx == 0 || dz == 0 || dx == 3 || dz == 3;
                b.set(x + dx, FEET_STREET, z + dz, rim ? "minecraft:smooth_sandstone" : "minecraft:water");
                b.set(x + dx, 6, z + dz, "minecraft:cut_sandstone_slab[type=bottom]");
            }
        }
        for (int[] c : new int[][]{{0, 0}, {3, 0}, {0, 3}, {3, 3}}) b.fill(x + c[0], FEET_STREET + 1, z + c[1], x + c[0], 5, z + c[1], "minecraft:spruce_fence");
        b.set(x + 1, 5, z + 1, "minecraft:lantern[hanging=true]");
    }

    /** A fountain: a one-block rim round water, an obelisk in the middle with a lantern on top. */
    private void fountain(int x, int z, int feet, int size) {
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                boolean rim = dx == 0 || dz == 0 || dx == size - 1 || dz == size - 1;
                b.set(x + dx, feet, z + dz, rim ? "minecraft:cut_sandstone" : "minecraft:water");
            }
        }
        int c = size / 2 - 1;
        b.fill(x + c, feet, z + c, x + c + 1, feet + 3, z + c + 1, "minecraft:chiseled_sandstone");
        b.set(x + c, feet + 4, z + c, "minecraft:lantern[hanging=false]");
    }

    private void planter(int x, int z, int feet) {
        b.fill(x, feet, z, x + 1, feet, z, "minecraft:terracotta");
        b.set(x, feet + 1, z, "minecraft:dead_bush");
    }

    /** A market stall against a wall: barrels as the counter, fence posts, a cloth roof out of reach. */
    private void stall(int x, int z, int len, boolean facesEast) {
        int feet = kind[x][z] == WADI ? FEET_WADI : FEET_STREET;
        for (int i = 0; i < len; i++) b.set(x, feet, z + i, "minecraft:barrel[facing=up,open=false]");
        int post = facesEast ? x + 1 : x - 1;
        b.fill(post, feet, z - 1, post, feet + 3, z - 1, "minecraft:spruce_fence");
        b.fill(post, feet, z + len, post, feet + 3, z + len, "minecraft:spruce_fence");
        String cloth = CLOTH[hash(x, z) % CLOTH.length];
        for (int i = -1; i <= len; i++) {
            b.set(x, feet + 4, z + i, cloth);
            b.set(post, feet + 4, z + i, cloth);
        }
    }

    /** A weathered boulder of sandstone, two high. */
    private void boulder(int x, int z) {
        b.fill(x, FEET_WADI, z, x + 1, FEET_WADI, z + 1, "minecraft:sandstone");
        b.set(x, FEET_WADI + 1, z, "minecraft:smooth_sandstone");
        b.set(x + 1, FEET_WADI + 1, z + 1, "minecraft:sandstone");
    }

    /** A palm: a slightly bent trunk and a crown of fronds drooping at their tips. */
    private void palm(int x, int feet, int z, int height) {
        for (int i = 0; i < height; i++) {
            int lean = i >= height - 2 ? 1 : 0;
            b.set(x + lean, feet + i, z, "minecraft:jungle_log[axis=y]");
        }
        int cx = x + 1, cy = feet + height, cz = z;
        leaf(cx, cy, cz);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            int len = d[0] != 0 && d[1] != 0 ? 2 : 3;
            for (int i = 1; i <= len; i++) leaf(cx + d[0] * i, cy, cz + d[1] * i);
            leaf(cx + d[0] * (len + 1), cy - 1, cz + d[1] * (len + 1));
        }
    }

    /** A frond where nothing else is (fronds never replace a wall or a roof). */
    private void leaf(int x, int y, int z) {
        if (b.get(x, y, z) == null) b.set(x, y, z, "minecraft:jungle_leaves[persistent=true,distance=1,waterlogged=false]");
    }

    /** A 3 × 5 letter painted on the wall at {@code x} (facing east into the courtyard) or {@code x} (facing west). */
    private void letter(String[] rows, int x, int z0, int y0, boolean facesEast, String paint) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 3; c++) {
                if (rows[r].charAt(c) != '#') continue;
                // looking west the left of the letter is south (+z); looking east it is north (-z)
                int z = facesEast ? z0 + 2 - c : z0 + c;
                b.set(x, y0 + rows.length - 1 - r, z, paint);
            }
        }
    }

    private static final String[] LETTER_A = {".#.", "#.#", "###", "#.#", "#.#"};
    private static final String[] LETTER_B = {"##.", "#.#", "##.", "#.#", "##."};

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }
}
