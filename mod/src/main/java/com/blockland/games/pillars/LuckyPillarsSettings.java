package com.blockland.games.pillars;

import com.blockland.games.common.TeamArenaSettings;
import com.blockland.games.common.WeightedItems;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 幸运高柱 mode settings: free for all ({@code players} one-player teams, one pillar each) with {@code mapIds},
 * {@code countdownSeconds} and {@code endingSeconds} as in {@link TeamArenaSettings}; every {@code itemIntervalSeconds}
 * each player still standing gets {@code itemsPerDrop} items drawn from {@code items} ({@code [{item, min, max, weight}]}).
 * Mirrors the backend's {@code LuckyPillarsDefinition}.
 */
record LuckyPillarsSettings(TeamArenaSettings team, int itemIntervalSeconds, int itemsPerDrop, List<WeightedItems.Entry> items) {

    static LuckyPillarsSettings parse(JsonObject json) {
        TeamArenaSettings base = TeamArenaSettings.parse(json);
        int players = TeamArenaSettings.intOr(json, "players", 16, 2, 64);
        return new LuckyPillarsSettings(
                new TeamArenaSettings(1, players, base.mapIds(), base.countdownSeconds(), base.endingSeconds()),
                TeamArenaSettings.intOr(json, "itemIntervalSeconds", 8, 1, 300),
                TeamArenaSettings.intOr(json, "itemsPerDrop", 1, 1, 8),
                List.copyOf(WeightedItems.parse(json, "items")));
    }
}
