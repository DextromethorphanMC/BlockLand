package com.blockland.server.ranked;

import com.blockland.common.RankTier;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The ranked ladder (排位) as other server modules see it — the profile tab, the match loading screen, the lobby — the
 * way {@code movement/Slides} is for sliding. The ranked service (backend client, matchmaking by score, results)
 * installs the view at start-up; without it everyone is unranked.
 */
public final class Ranks {
    /**
     * One player's standing.
     *
     * @param position place on the whole server's ladder, 1 = best, 0 = not placed yet (no ranked match played)
     * @param matches  ranked matches played (placement matches count)
     */
    public record RankInfo(int score, RankTier tier, int position, int matches, int wins) {
        public static final RankInfo UNRANKED = new RankInfo(0, RankTier.COAL, 0, 0, 0);
    }

    /** A line of the ladder. */
    public record Entry(UUID player, String name, int score, RankTier tier, int position) {
    }

    public interface View {
        View NONE = new View() {
            @Override public RankInfo rankOf(UUID player) { return RankInfo.UNRANKED; }
            @Override public CompletableFuture<List<Entry>> leaderboard(int limit) { return CompletableFuture.completedFuture(List.of()); }
        };

        /** The cached standing (never blocks; refreshed by the service when players join and after ranked matches). */
        RankInfo rankOf(UUID player);

        /** The top of the ladder, best first (from the backend). */
        CompletableFuture<List<Entry>> leaderboard(int limit);
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
