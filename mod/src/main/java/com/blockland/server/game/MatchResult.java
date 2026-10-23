package com.blockland.server.game;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The end of a match as a game describes it: who won, why, and one row of statistics per participant. Neutral data:
 * the game decides what the columns mean (kills, infections, …), {@link MatchResultPublisher} turns it into each
 * player's own results panel. Texts are components, so every player reads them in their own language.
 *
 * @param headline e.g. "人类阵营获胜" or "红队获胜"
 * @param reason   why the match ended ("时间到"), or null
 * @param columns  statistic column titles, matching every row's {@code values}
 * @param rows     participants, best first
 */
public record MatchResult(String modeId, String modeTitle, Component gameName, Component headline, @Nullable Component reason,
                          int durationSeconds, List<Component> columns, List<Row> rows) {

    public enum Outcome { VICTORY, DEFEAT, DRAW }

    /**
     * @param team      side or team label shown next to the name ("人类", "红队")
     * @param teamColor ARGB colour of that label
     * @param outcome   how the match ended for this player
     */
    public record Row(UUID player, String name, Component team, int teamColor, List<Component> values, Outcome outcome, boolean mvp) {
    }

    public MatchResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
