package com.blockland.server.game;

/** How many players a match needs: {@code teamCount} teams of {@code teamSize} players. */
public record MatchFormat(int teamSize, int teamCount) {

    public MatchFormat {
        teamSize = Math.max(1, teamSize);
        teamCount = Math.max(1, teamCount);
    }

    public int requiredPlayers() {
        return teamSize * teamCount;
    }

    /** e.g. "1v1", "4v4", "8人混战", "2人×4队". */
    public String label() {
        if (teamCount == 2) return teamSize + "v" + teamSize;
        if (teamSize == 1) return teamCount + "人混战";
        return teamSize + "人×" + teamCount + "队";
    }
}
