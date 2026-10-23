package com.blockland.server.game;

import net.minecraft.network.chat.Component;

/** How many players a match needs: {@code teamCount} teams of {@code teamSize} players. */
public record MatchFormat(int teamSize, int teamCount) {

    public MatchFormat {
        teamSize = Math.max(1, teamSize);
        teamCount = Math.max(1, teamCount);
    }

    public int requiredPlayers() {
        return teamSize * teamCount;
    }

    /** e.g. "1v1", "4v4", "8人混战", "2人×4队", "8人" (one side, e.g. infection). */
    public Component label() {
        if (teamCount == 1) return Component.translatable("blockland.format.players", teamSize);
        if (teamCount == 2) return Component.literal(teamSize + "v" + teamSize);
        if (teamSize == 1) return Component.translatable("blockland.format.free_for_all", teamCount);
        return Component.translatable("blockland.format.teams", teamSize, teamCount);
    }
}
