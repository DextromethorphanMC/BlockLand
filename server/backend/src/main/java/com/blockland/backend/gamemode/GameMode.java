package com.blockland.backend.gamemode;

import com.blockland.backend.kit.KitItem;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * A playable mode in "小游戏管理".
 *
 * @param type             game type id, e.g. {@code SKYWARS}
 * @param timeLimitSeconds optional match duration ("存活时间"); null = unlimited
 * @param availableUntil   end of a limited-time mode ("限时开放"); the Minecraft server closes the mode after it.
 *                         null = permanent
 * @param settings         type specific settings, validated by the matching {@code GameTypeDefinition}
 * @param kit              starting items; only used by game types that support kits
 * @param coverVersion     version of the cover image in the media store ({@code mode/<id>}); null = no cover
 */
public record GameMode(
        String id,
        String title,
        String type,
        String description,
        boolean enabled,
        Integer timeLimitSeconds,
        Instant availableUntil,
        ObjectNode settings,
        List<KitItem> kit,
        String coverVersion,
        Instant updatedAt
) {
    public GameMode {
        if (kit == null) kit = List.of();
    }

    public GameMode withSettings(ObjectNode newSettings, Instant time) {
        return new GameMode(id, title, type, description, enabled, timeLimitSeconds, availableUntil, newSettings, kit, coverVersion, time);
    }

    public GameMode withCover(String version, Instant time) {
        return new GameMode(id, title, type, description, enabled, timeLimitSeconds, availableUntil, settings, kit, version, time);
    }
}
