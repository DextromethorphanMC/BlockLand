package com.blockland.games.infection;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 枪战感染 mode settings. Mirrors the backend's {@code InfectionDefinition}.
 *
 * @param players                exact number of players per match
 * @param infectionDelaySeconds  seconds into each round before the mother monsters appear
 * @param startMonsters          players who start each round as mother monsters (at least one human always stays)
 * @param rounds                 best of this many rounds: the side with more round wins takes the match
 * @param roundBreakSeconds      pause between rounds
 * @param monsterDamage          left-click damage of every monster (the same for all monster types)
 * @param selectSeconds          time to choose a monster before the first offered one is used
 * @param monsters               monster ids offered, first = default (empty = every monster)
 */
public record InfectionSettings(int players, List<String> mapIds, int countdownSeconds, int endingSeconds,
                                int infectionDelaySeconds, int startMonsters, int rounds, int roundBreakSeconds,
                                float motherHealthMultiplier, float monsterDamage, int monsterRespawnSeconds,
                                int selectSeconds, List<String> monsters) {

    public static InfectionSettings parse(JsonObject json) {
        JsonObject s = json == null ? new JsonObject() : json;
        return new InfectionSettings(
                TeamArenaSettings.intOr(s, "players", 8, 2, 32),
                strings(s, "mapIds"),
                TeamArenaSettings.intOr(s, "countdownSeconds", 10, 0, 120),
                TeamArenaSettings.intOr(s, "endingSeconds", 8, 1, 60),
                TeamArenaSettings.intOr(s, "infectionDelaySeconds", 15, 3, 60),
                TeamArenaSettings.intOr(s, "startMonsters", 1, 1, 31),
                TeamArenaSettings.intOr(s, "rounds", 3, 1, 15),
                TeamArenaSettings.intOr(s, "roundBreakSeconds", 6, 3, 30),
                floatOr(s, "motherHealthMultiplier", 2f, 1f, 5f),
                floatOr(s, "monsterDamage", 6f, 1f, 40f),
                TeamArenaSettings.intOr(s, "monsterRespawnSeconds", 5, 1, 30),
                TeamArenaSettings.intOr(s, "selectSeconds", 10, 3, 30),
                strings(s, "monsters"));
    }

    /** Round wins needed to take the match (a majority of the rounds). */
    public int winsNeeded() {
        return rounds / 2 + 1;
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> values = new ArrayList<>();
        if (json.has(key) && json.get(key).isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray(key)) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) values.add(e.getAsString().trim());
            }
        }
        return List.copyOf(values);
    }

    private static float floatOr(JsonObject json, String key, float fallback, float min, float max) {
        float value;
        try {
            value = json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsFloat() : fallback;
        } catch (Exception e) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
