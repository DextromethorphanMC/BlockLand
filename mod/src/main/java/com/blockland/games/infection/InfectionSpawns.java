package com.blockland.games.infection;

import com.blockland.server.content.model.MapDef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where infection players appear, from the map's type-owned settings ({@code settings.spawns}, each point marked
 * HUMAN, MONSTER or BOTH; edited in the admin map editor). Maps without them fall back to the generic team spawns:
 * group 0 = humans, group 1 = monsters, a single group = both. Positions are relative to the map origin.
 */
record InfectionSpawns(List<Point> humans, List<Point> monsters) {
    enum Role { HUMAN, MONSTER, BOTH }

    record Point(double x, double y, double z, float yaw) {
    }

    boolean isEmpty() {
        return humans.isEmpty();
    }

    static InfectionSpawns of(MapDef map) {
        List<Point> humans = new ArrayList<>(), monsters = new ArrayList<>();
        JsonObject settings = map.settings;
        if (settings != null && settings.has("spawns") && settings.get("spawns").isJsonArray()) {
            for (JsonElement e : settings.getAsJsonArray("spawns")) {
                if (!e.isJsonObject()) continue;
                JsonObject p = e.getAsJsonObject();
                Point point = new Point(number(p, "x"), number(p, "y"), number(p, "z"), (float) number(p, "yaw"));
                Role role = role(p);
                if (role != Role.MONSTER) humans.add(point);
                if (role != Role.HUMAN) monsters.add(point);
            }
        }
        if (humans.isEmpty() && monsters.isEmpty() && map.teamSpawns != null) {
            List<MapDef.TeamSpawnDef> groups = map.teamSpawns.stream().filter(t -> t.points != null && !t.points.isEmpty()).toList();
            for (int g = 0; g < groups.size() && g < 2; g++) {
                for (MapDef.SpawnPointDef s : groups.get(g).points) {
                    Point point = new Point(s.x, s.y, s.z, s.yaw);
                    if (g == 0) humans.add(point);
                    if (g == 1 || groups.size() == 1) monsters.add(point);
                }
            }
        }
        // a map with only monster points still needs somewhere to start the round, and the other way round
        if (humans.isEmpty()) humans.addAll(monsters);
        if (monsters.isEmpty()) monsters.addAll(humans);
        return new InfectionSpawns(List.copyOf(humans), List.copyOf(monsters));
    }

    private static Role role(JsonObject p) {
        try {
            return p.has("role") ? Role.valueOf(p.get("role").getAsString().toUpperCase(Locale.ROOT)) : Role.BOTH;
        } catch (IllegalArgumentException e) {
            return Role.BOTH;
        }
    }

    private static double number(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key).getAsDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
