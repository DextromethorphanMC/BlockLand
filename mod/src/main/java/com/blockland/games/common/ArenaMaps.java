package com.blockland.games.common;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.MapDef;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Picks the maps a team arena mode can be played on. */
public final class ArenaMaps {
    private ArenaMaps() {
    }

    public static List<MapDef> eligible(String gameTypeId, TeamArenaSettings settings, ContentSnapshot content) {
        List<MapDef> candidates = settings.mapIds().isEmpty()
                ? content.maps.stream().filter(m -> gameTypeId.equalsIgnoreCase(m.gameType)).toList()
                : settings.mapIds().stream().map(content::map).flatMap(Optional::stream).toList();
        return candidates.stream()
                .filter(m -> m.fileHash != null && m.format != null)
                .filter(m -> m.format.toLowerCase(Locale.ROOT).matches("nbt|schem"))
                .filter(m -> usableSpawnGroups(m) >= settings.teamCount())
                .toList();
    }

    public static long usableSpawnGroups(MapDef map) {
        return map.teamSpawns == null ? 0 : map.teamSpawns.stream().filter(t -> t.points != null && !t.points.isEmpty()).count();
    }

    public static String noMapsReason(TeamArenaSettings settings) {
        return "没有可用地图（需要至少 " + settings.teamCount() + " 个队伍出生点）";
    }
}
