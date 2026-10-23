package com.blockland.games.common;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.MapDef;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Picks the maps a mode can be played on. */
public final class ArenaMaps {
    private ArenaMaps() {
    }

    /**
     * Maps with a usable file: the listed ones, or every map of the game type when none are listed. Game types
     * then apply their own spawn requirements.
     */
    public static List<MapDef> candidates(String gameTypeId, List<String> mapIds, ContentSnapshot content) {
        List<MapDef> candidates = mapIds.isEmpty()
                ? content.maps.stream().filter(m -> gameTypeId.equalsIgnoreCase(m.gameType)).toList()
                : mapIds.stream().map(content::map).flatMap(Optional::stream).toList();
        return candidates.stream()
                .filter(m -> m.fileHash != null && m.format != null)
                .filter(m -> m.format.toLowerCase(Locale.ROOT).matches("nbt|schem"))
                .toList();
    }

    /** Team arena maps: a usable file with a spawn group for every team. */
    public static List<MapDef> eligible(String gameTypeId, TeamArenaSettings settings, ContentSnapshot content) {
        return candidates(gameTypeId, settings.mapIds(), content).stream()
                .filter(m -> usableSpawnGroups(m) >= settings.teamCount())
                .toList();
    }

    /** The map the players asked for when the mode offers it, otherwise a random one of {@code maps} (not empty). */
    public static MapDef pick(List<MapDef> maps, String requested) {
        for (MapDef map : maps) {
            if (map.id.equals(requested)) return map;
        }
        return maps.get(ThreadLocalRandom.current().nextInt(maps.size()));
    }

    public static long usableSpawnGroups(MapDef map) {
        return map.teamSpawns == null ? 0 : map.teamSpawns.stream().filter(t -> t.points != null && !t.points.isEmpty()).count();
    }

    public static Component noMapsReason(TeamArenaSettings settings) {
        return Component.translatable("blockland.arena.no_maps", settings.teamCount());
    }
}
