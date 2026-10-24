package com.blockland.backend.ranked;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's place on the ranked ladder in the current season, and how earlier seasons ended for them.
 *
 * @param reachedAt when the score last changed (equal scores: who got there first ranks higher)
 */
public record Rating(String uuid, String name, int score, int matches, int wins, int losses, int draws, Instant reachedAt,
                     List<SeasonEnd> seasons) {
    public Rating {
        seasons = seasons == null ? List.of() : List.copyOf(seasons);
    }

    /** How a season ended for the player (kept when the ladder is reset). */
    public record SeasonEnd(int season, int score, String tier, int position, int matches, int wins) {
    }

    static Rating fresh(String uuid, String name, int startScore) {
        return new Rating(uuid, name, startScore, 0, 0, 0, 0, null, List.of());
    }

    Rating played(String newName, int newScore, String outcome, Instant now) {
        return new Rating(uuid, newName == null || newName.isBlank() ? name : newName, Math.max(0, newScore), matches + 1,
                wins + ("VICTORY".equals(outcome) ? 1 : 0), losses + ("DEFEAT".equals(outcome) ? 1 : 0),
                draws + ("DRAW".equals(outcome) ? 1 : 0), now, seasons);
    }

    /** Back to the start for a new season; the old season's end is kept. */
    Rating reset(int startScore, SeasonEnd end) {
        List<SeasonEnd> history = new ArrayList<>(seasons);
        if (end != null) history.add(end);
        return new Rating(uuid, name, startScore, 0, 0, 0, 0, null, history);
    }
}
