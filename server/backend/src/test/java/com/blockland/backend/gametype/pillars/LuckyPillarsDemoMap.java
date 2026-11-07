package com.blockland.backend.gametype.pillars;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.StructureBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generated 幸运高柱 map: a ring of one block wide bedrock pillars (survival cannot break them), each rising from a small
 * patch of ground, around a small island in the middle, all over the void. One team spawn group per pillar, on its top,
 * facing the middle. Real maps are imported in the admin UI.
 */
public final class LuckyPillarsDemoMap {
    /** Top surface of the ground patches and the middle island. */
    public static final int GROUND_Y = 4;
    /** Free space around the ring and above the pillar tops (for building). */
    private static final int MARGIN = 6, HEADROOM = 12;
    /** Relative Y below which players count as fallen into the void. */
    public static final int VOID_Y = -6;
    private static final String PILLAR = "minecraft:bedrock";
    private static final String GRASS = "minecraft:grass_block[snowy=false]";

    /**
     * @param spawns    one group per pillar, on top of it
     * @param pillarTop relative Y of every pillar's top block
     */
    public record Result(byte[] data, int sizeX, int sizeY, int sizeZ, List<GameMap.TeamSpawn> spawns, int pillarTop) {
    }

    private final int pillars;
    private final int ringRadius;
    private final int height;
    private final int size;
    private final int sizeY;
    private final int center;
    private final Random random;
    private final StructureBuilder builder;

    private LuckyPillarsDemoMap(int pillars, int ringRadius, int height, long seed) {
        this.pillars = pillars;
        this.ringRadius = ringRadius;
        this.height = height;
        this.size = 2 * (ringRadius + MARGIN) + 1;
        this.sizeY = GROUND_Y + height + HEADROOM;
        this.center = ringRadius + MARGIN;
        this.random = new Random(seed);
        this.builder = new StructureBuilder(size, sizeY, size);
    }

    /**
     * @param pillars    pillars (= players) on the ring
     * @param ringRadius distance of the pillars from the middle
     * @param height     blocks from the ground to the pillar tops
     */
    public static Result generate(int pillars, int ringRadius, int height, long seed) {
        return new LuckyPillarsDemoMap(pillars, ringRadius, height, seed).build();
    }

    private Result build() {
        int top = GROUND_Y + height;
        patch(center, center, Math.max(3, ringRadius / 3), 4);
        List<GameMap.TeamSpawn> spawns = new ArrayList<>();
        for (int i = 0; i < pillars; i++) {
            // the first pillar to the north, then clockwise seen from above
            double angle = -Math.PI / 2 + Math.PI * 2 * i / pillars;
            double dirX = Math.cos(angle), dirZ = Math.sin(angle);
            int x = (int) Math.round(center + dirX * ringRadius);
            int z = (int) Math.round(center + dirZ * ringRadius);
            patch(x, z, 2, 3);
            builder.fill(x, GROUND_Y + 1, z, x, top, z, PILLAR);
            float yaw = (float) Math.round(Math.toDegrees(Math.atan2(dirX, -dirZ)));
            spawns.add(new GameMap.TeamSpawn("高柱 " + (i + 1), List.of(new GameMap.SpawnPoint(x, top + 1, z, yaw))));
        }
        return new Result(builder.build(), size, sizeY, size, spawns, top);
    }

    /** A small round patch of ground: grass on dirt, tapering into stone below, with a ragged rim and some grass on it. */
    private void patch(int cx, int cz, int radius, int depth) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                // the rim loses a few blocks so the patches do not look stamped out
                if (distance > radius + 0.4 || (distance > radius - 0.6 && random.nextInt(3) == 0)) continue;
                int x = cx + dx, z = cz + dz;
                int bottom = GROUND_Y - (int) Math.round((depth - 1) * (1 - distance / (radius + 1)));
                for (int y = bottom; y <= GROUND_Y; y++) {
                    builder.set(x, y, z, y == GROUND_Y ? GRASS : y >= GROUND_Y - 1 ? "minecraft:dirt" : "minecraft:stone");
                }
                if ((dx != 0 || dz != 0) && random.nextInt(5) == 0) builder.set(x, GROUND_Y + 1, z, "minecraft:short_grass");
            }
        }
    }
}
