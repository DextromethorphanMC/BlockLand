package com.blockland.server.content.model;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An imported map file plus its generic metadata. All coordinates are relative to the map origin
 * (the minimum corner of the structure/schematic). Game type specific data lives in {@link #settings}.
 */
public final class MapDef {
    public String id;
    public String name;
    public String gameType;
    /** {@code nbt} (vanilla structure) or {@code schem} (Sponge schematic v2/v3). */
    public String format;
    public String fileHash;
    public Size size = new Size();
    /** Optional block id that marks spawn points in the file; such blocks are not pasted. */
    public String spawnMarkerBlock;
    public List<TeamSpawnDef> teamSpawns = new ArrayList<>();
    /** Relative Y below which players are considered fallen into the void. */
    public Integer voidY;
    /** Owned by the map's game type (e.g. SkyWars chests). */
    public JsonObject settings = new JsonObject();
    /** Version of the cover image ({@code map/<id>}); null = no cover. */
    public String coverVersion;

    public static final class Size {
        public int x;
        public int y;
        public int z;
    }

    public static final class TeamSpawnDef {
        public String name;
        public List<SpawnPointDef> points = new ArrayList<>();
    }

    public static final class SpawnPointDef {
        public double x;
        public double y;
        public double z;
        public float yaw;
    }
}
