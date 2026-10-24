package com.blockland.server.ranked;

import com.blockland.common.RankTier;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The ranked ladders (排位) as other server modules see it — the profile tab, the match loading screen, the lobby — the
 * way {@code movement/Slides} is for sliding. Every ranked mode has a ladder of its own: a player's score, tier and place
 * are always asked for a mode. The ranked service (backend client, matchmaking by score, results) installs the view at
 * start-up; without it everyone is unranked.
 */
public final class Ranks {
    /**
     * One player's standing in one mode.
     *
     * @param position place on the mode's ladder, 1 = best, 0 = not placed yet (no ranked match played in it)
     * @param matches  ranked matches played in the mode (placement matches count)
     */
    public record RankInfo(int score, RankTier tier, int position, int matches, int wins) {
        public static final RankInfo UNRANKED = new RankInfo(0, RankTier.COAL, 0, 0, 0);
    }

    /** A line of a mode's ladder. */
    public record Entry(UUID player, String name, int score, RankTier tier, int position) {
    }

    /** A mode whose matches count for its ladder. */
    public record RankedMode(String id, String title) {
    }

    public interface View {
        View NONE = new View() {
            @Override public RankInfo rankOf(UUID player, String modeId) { return RankInfo.UNRANKED; }
            @Override public Map<String, RankInfo> ranksOf(UUID player) { return Map.of(); }
            @Override public List<RankedMode> rankedModes() { return List.of(); }
            @Override public CompletableFuture<List<Entry>> leaderboard(String modeId, int limit) { return CompletableFuture.completedFuture(List.of()); }
        };

        /**
         * The cached standing in a mode (never blocks; refreshed by the service when players join and after ranked
         * matches): the start standing in a mode the player has not played, {@link RankInfo#UNRANKED} while unknown.
         */
        RankInfo rankOf(UUID player, String modeId);

        /** The cached standings of every mode the player has played ranked, by mode id. */
        Map<String, RankInfo> ranksOf(UUID player);

        /** The modes on offer whose matches count, in the order of the content. */
        List<RankedMode> rankedModes();

        /** The top of a mode's ladder, best first (from the backend). */
        CompletableFuture<List<Entry>> leaderboard(String modeId, int limit);
    }

    private static volatile View view = View.NONE;

    private Ranks() {
    }

    public static void setView(@Nullable View next) {
        view = next == null ? View.NONE : next;
    }

    public static View get() {
        return view;
    }
}
