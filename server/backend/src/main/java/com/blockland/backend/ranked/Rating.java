package com.blockland.backend.ranked;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's place on one mode's ranked ladder in the current season, and how earlier seasons in that mode ended for
 * them. Ratings written before the ladders were per mode have no {@code modeId}; no ladder reads them.
 *
 * @param reachedAt when the score last changed (equal scores: who got there first ranks higher)
 */
public record Rating(String modeId, String uuid, String name, int score, int matches, int wins, int losses, int draws, Instant reachedAt,
                     List<SeasonEnd> seasons) {
    public Rating {
        seasons = seasons == null ? List.of() : List.copyOf(seasons);
    }

    /** How a season ended for the player in this mode (kept when the ladder is reset). */
    public record SeasonEnd(int season, int score, String tier, int position, int matches, int wins) {
    }

    /** The storage key: one rating per player and mode. */
    public static String key(String modeId, String uuid) {
        return (modeId == null ? "" : modeId) + "|" + uuid;
    }

    public String key() {
        return key(modeId, uuid);
    }

    static Rating fresh(String modeId, String uuid, String name, int startScore) {
        return new Rating(modeId, uuid, name, startScore, 0, 0, 0, 0, null, List.of());
    }

    Rating played(String newName, int newScore, String outcome, Instant now) {
        return new Rating(modeId, uuid, newName == null || newName.isBlank() ? name : newName, Math.max(0, newScore), matches + 1,
                wins + ("VICTORY".equals(outcome) ? 1 : 0), losses + ("DEFEAT".equals(outcome) ? 1 : 0),
                draws + ("DRAW".equals(outcome) ? 1 : 0), now, seasons);
    }

    /** Back to the start for a new season; the old season's end is kept. */
    Rating reset(int startScore, SeasonEnd end) {
        List<SeasonEnd> history = new ArrayList<>(seasons);
        if (end != null) history.add(end);
        return new Rating(modeId, uuid, name, startScore, 0, 0, 0, 0, null, history);
    }
}
