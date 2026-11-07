package com.blockland.backend.gametype.skywars;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Generated demo SkyWars map: eight islands around a centre island. Real maps are imported in the admin UI. */
public final class SkyWarsDemoMap {
    public static final int SIZE_X = 96;
    public static final int SIZE_Y = 24;
    public static final int SIZE_Z = 96;
    public static final int ISLANDS = 8;
    private static final int CENTER = 48;
    private static final int TOP = 12;
    private static final int RING_RADIUS = 34;
    private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

    public record Result(byte[] data, List<GameMap.TeamSpawn> spawns, List<SkyWarsMapSettings.Chest> chests) {
    }

    private final StructureBuilder builder = new StructureBuilder(SIZE_X, SIZE_Y, SIZE_Z);

    private SkyWarsDemoMap() {
    }

    public static Result generate() {
        return new SkyWarsDemoMap().build();
    }

    private Result build() {
        List<GameMap.TeamSpawn> spawns = new ArrayList<>();
        List<SkyWarsMapSettings.Chest> chests = new ArrayList<>();

        island(CENTER, CENTER, 7);
        builder.set(CENTER, TOP, CENTER, "minecraft:gold_block");
        int[][] centerChests = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] c : centerChests) {
            chest(CENTER + c[0], TOP + 1, CENTER + c[1]);
            chests.add(new SkyWarsMapSettings.Chest(CENTER + c[0], TOP + 1, CENTER + c[1], "center", "中心箱子"));
        }

        for (int i = 0; i < ISLANDS; i++) {
            double angle = Math.PI * 2 * i / ISLANDS;
            double dirX = Math.cos(angle), dirZ = Math.sin(angle);
            int ix = (int) Math.round(CENTER + dirX * RING_RADIUS);
            int iz = (int) Math.round(CENTER + dirZ * RING_RADIUS);
            island(ix, iz, 4);

            int chestX = ix + (int) Math.round(dirX * 3), chestZ = iz + (int) Math.round(dirZ * 3);
            chest(chestX, TOP + 1, chestZ);
            chests.add(new SkyWarsMapSettings.Chest(chestX, TOP + 1, chestZ, "island", "出生岛 " + (i + 1)));

            // a small tree on the left side of the island
            int treeX = ix + (int) Math.round(-dirZ * 3), treeZ = iz + (int) Math.round(dirX * 3);
            builder.fill(treeX, TOP + 1, treeZ, treeX, TOP + 3, treeZ, "minecraft:oak_log[axis=y]");
            String leaves = "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]";
            builder.fill(treeX - 1, TOP + 4, treeZ - 1, treeX + 1, TOP + 4, treeZ + 1, leaves);
            builder.set(treeX, TOP + 5, treeZ, leaves);

            float yaw = (float) Math.round(Math.toDegrees(Math.atan2(dirX, -dirZ)));
            List<GameMap.SpawnPoint> points = List.of(
                    new GameMap.SpawnPoint(ix, TOP + 1, iz, yaw),
                    new GameMap.SpawnPoint(ix - (int) Math.round(dirZ * 2), TOP + 1, iz + (int) Math.round(dirX * 2), yaw),
                    new GameMap.SpawnPoint(ix + (int) Math.round(dirZ * 2), TOP + 1, iz - (int) Math.round(dirX * 2), yaw),
                    new GameMap.SpawnPoint(ix - (int) Math.round(dirX * 2), TOP + 1, iz - (int) Math.round(dirZ * 2), yaw));
            spawns.add(new GameMap.TeamSpawn("出生岛 " + (i + 1), points));
        }
        return new Result(builder.build(), spawns, chests);
    }

    private void chest(int x, int y, int z) {
        builder.set(x, y, z, CHEST, Map.of("id", "minecraft:chest"));
    }

    private void island(int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > radius + 0.3) continue;
                builder.set(cx + dx, TOP, cz + dz, "minecraft:grass_block[snowy=false]");
                builder.set(cx + dx, TOP - 1, cz + dz, "minecraft:dirt");
                for (int depth = 2; depth <= radius + 1; depth++) {
                    if (dist <= radius + 1.3 - depth) builder.set(cx + dx, TOP - depth, cz + dz, "minecraft:stone");
                }
            }
        }
    }
}
