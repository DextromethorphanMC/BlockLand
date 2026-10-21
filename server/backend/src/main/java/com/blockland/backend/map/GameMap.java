package com.blockland.backend.map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * An imported map file and its generic game metadata. All coordinates are relative to the map's minimum corner.
 * Anything specific to one minigame (e.g. SkyWars chests) lives in {@link #settings}, owned by that game type.
 *
 * @param format           {@code nbt} (vanilla structure) or {@code schem} (Sponge schematic v2/v3)
 * @param spawnMarkerBlock block id marking spawn points inside the file (removed when the map is pasted)
 * @param voidY            relative Y below which players count as fallen into the void
 * @param settings         game type specific map data, validated by the map's {@code GameTypeDefinition}
 */
public record GameMap(
        String id,
        String name,
        String gameType,
        String format,
        String fileName,
        String fileHash,
        long fileSize,
        Size size,
        String spawnMarkerBlock,
        List<TeamSpawn> teamSpawns,
        Integer voidY,
        ObjectNode settings,
        Instant updatedAt
) {
    public record Size(int x, int y, int z) {
    }

    public record TeamSpawn(String name, List<SpawnPoint> points) {
    }

    public record SpawnPoint(double x, double y, double z, float yaw) {
    }

    public long usableTeamSpawns() {
        return teamSpawns == null ? 0 : teamSpawns.stream().filter(t -> t.points() != null && !t.points().isEmpty()).count();
    }
}
