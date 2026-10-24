package com.blockland.backend.ranked;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.storage.Repository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server-wide ranked ladder (排位) over every ranked mode. Game servers report each ranked match once it is decided;
 * scores move by team Elo — a team's strength is its players' average score, every pair of teams is a game (the better
 * placement, or VICTORY over DEFEAT, wins it; equal ones draw) — times each player's K (larger in their placement
 * matches). Reports are idempotent per match id. Places on the ladder count players who played at least one match, by
 * score (equal scores: who reached it first). Tiers come from the score thresholds; 猎首 also needs a place in the top
 * {@link RankedConfig#headhunterTop}. Must stay in sync with the mod's {@code common/RankTier} and ranked service.
 */
@Service
public class RankedService {
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{32,36}$");
    private static final Set<String> OUTCOMES = Set.of("VICTORY", "DEFEAT", "DRAW");
    /** Counted matches kept for idempotent retries (the oldest are dropped). */
    private static final int KEPT_MATCHES = 5000;
    static final int MAX_LEADERBOARD = 500;

    public enum Tier {
        COAL, IRON, GOLD, DIAMOND, HEADHUNTER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A player's standing as the game server sees it. {@code position} 0 = not placed yet. */
    public record Standing(int score, String tier, int position, int matches, int wins) {
    }

    /** One line of the ladder. */
    public record LeaderEntry(String uuid, String name, int score, String tier, int position, int matches, int wins) {
    }

    /** A reported team: its players, its outcome and (free for all) its placement, 1 = best, 0 = none. */
    public record ResultTeam(List<String> players, String outcome, Integer placement) {
    }

    public record ResultRequest(String matchId, String modeId, List<ResultTeam> teams) {
    }

    /** What a match did to one player. */
    public record Change(String uuid, int before, int after, int delta, String tier, String tierBefore, int position) {
    }

    /** Admin view of one player: the standing plus the record and earlier seasons. */
    public record PlayerView(String uuid, String name, Standing standing, int losses, int draws, List<Rating.SeasonEnd> seasons) {
    }

    private final Repository<Rating> ratings;
    private final Repository<RankedMatch> matches;
    private final RankedConfigStore config;
    private final PlayerService players;

    public RankedService(@Qualifier("rankedRatings") Repository<Rating> ratings, @Qualifier("rankedMatches") Repository<RankedMatch> matches,
                         RankedConfigStore config, PlayerService players) {
        this.ratings = ratings;
        this.matches = matches;
        this.config = config;
        this.players = players;
    }

    // ---- reading --------------------------------------------------------------------------------

    public synchronized Map<String, Standing> standings(List<String> uuids) {
        Map<String, Integer> positions = positions();
        RankedConfig c = config.get();
        Map<String, Standing> out = new LinkedHashMap<>();
        if (uuids == null) return out;
        for (String raw : uuids) {
            if (raw == null || !UUID_PATTERN.matcher(raw).matches()) continue;
            String uuid = normalize(raw);
            Rating rating = ratings.findById(uuid).orElse(null);
            out.put(raw, standing(rating, positions.getOrDefault(uuid, 0), c));
        }
        return out;
    }

    public synchronized List<LeaderEntry> leaderboard(int limit) {
        int n = Math.max(1, Math.min(MAX_LEADERBOARD, limit));
        RankedConfig c = config.get();
        List<Rating> ladder = ladder();
        List<LeaderEntry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, ladder.size()); i++) {
            Rating r = ladder.get(i);
            out.add(new LeaderEntry(r.uuid(), r.name(), r.score(), tier(r.score(), i + 1, c).id(), i + 1, r.matches(), r.wins()));
        }
        return out;
    }

    public synchronized PlayerView player(String uuid) {
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches()) throw ApiException.badRequest("无效的 UUID: " + uuid);
        String id = normalize(uuid);
        Rating rating = ratings.findById(id).orElse(null);
        Standing standing = standing(rating, positions().getOrDefault(id, 0), config.get());
        String name = rating != null ? rating.name() : players.status(id).map(PlayerService.PlayerStatus::name).orElse(null);
        return new PlayerView(id, name, standing, rating == null ? 0 : rating.losses(), rating == null ? 0 : rating.draws(),
                rating == null ? List.of() : rating.seasons());
    }

    public RankedConfig config() {
        return config.get();
    }

    // ---- a match was decided --------------------------------------------------------------------

    public synchronized List<Change> report(ResultRequest request) {
        List<String> errors = new ArrayList<>();
        if (request == null || request.matchId() == null || request.matchId().isBlank() || request.matchId().length() > 64) {
            throw ApiException.badRequest("缺少或无效的 matchId");
        }
        Optional<RankedMatch> counted = matches.findById(request.matchId());
        if (counted.isPresent()) return counted.get().changes();

        List<List<String>> teams = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<Integer> placements = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResultTeam team : request.teams() == null ? List.<ResultTeam>of() : request.teams()) {
            if (team == null || team.players() == null) continue;
            String outcome = team.outcome() == null ? "DRAW" : team.outcome().trim().toUpperCase(Locale.ROOT);
            if (!OUTCOMES.contains(outcome)) errors.add("无效的结果: " + team.outcome());
            List<String> members = new ArrayList<>();
            for (String p : team.players()) {
                if (p == null || !UUID_PATTERN.matcher(p).matches()) {
                    errors.add("无效的 UUID: " + p);
                    continue;
                }
                String id = normalize(p);
                if (!seen.add(id)) errors.add("玩家重复出现: " + p);
                else members.add(id);
            }
            if (members.isEmpty()) continue;
            teams.add(members);
            outcomes.add(outcome);
            placements.add(team.placement() == null ? 0 : Math.max(0, team.placement()));
        }
        if (!errors.isEmpty()) throw ApiException.invalid(errors);

        RankedConfig c = config.get();
        Map<String, Integer> positionsBefore = positions();
        Map<String, Rating> current = new HashMap<>();
        for (List<String> team : teams) {
            for (String id : team) current.put(id, ratings.findById(id).orElseGet(() -> Rating.fresh(id, nameOf(id), c.startScore())));
        }
        // nothing to win against: a match force-started with one side changes nobody
        if (teams.size() < 2) {
            List<Change> none = new ArrayList<>();
            for (Rating r : current.values()) {
                int position = positionsBefore.getOrDefault(r.uuid(), 0);
                String tier = tier(r.score(), position, c).id();
                none.add(new Change(r.uuid(), r.score(), r.score(), 0, tier, tier, position));
            }
            return none;
        }

        double[] strength = new double[teams.size()];
        for (int t = 0; t < teams.size(); t++) {
            strength[t] = teams.get(t).stream().mapToInt(id -> current.get(id).score()).average().orElse(c.startScore());
        }
        Instant now = Instant.now();
        List<Rating> updated = new ArrayList<>();
        Map<String, Integer> before = new LinkedHashMap<>();
        for (int t = 0; t < teams.size(); t++) {
            double sum = 0;
            for (int o = 0; o < teams.size(); o++) {
                if (o == t) continue;
                double expected = 1 / (1 + Math.pow(10, (strength[o] - strength[t]) / 400));
                sum += actual(outcomes.get(t), placements.get(t), outcomes.get(o), placements.get(o)) - expected;
            }
            double share = sum / (teams.size() - 1);
            for (String id : teams.get(t)) {
                Rating r = current.get(id);
                int k = r.matches() < c.placementMatches() ? c.placementK() : c.kFactor();
                before.put(id, r.score());
                updated.add(r.played(nameOf(id), r.score() + (int) Math.round(k * share), outcomes.get(t), now));
            }
        }
        ratings.saveAll(updated);

        Map<String, Integer> positionsAfter = positions();
        List<Change> changes = new ArrayList<>();
        for (Rating r : updated) {
            int old = before.get(r.uuid());
            int position = positionsAfter.getOrDefault(r.uuid(), 0);
            changes.add(new Change(r.uuid(), old, r.score(), r.score() - old, tier(r.score(), position, c).id(),
                    tier(old, positionsBefore.getOrDefault(r.uuid(), 0), c).id(), position));
        }
        matches.save(new RankedMatch(request.matchId(), request.modeId(), c.season(), now, changes));
        pruneMatches();
        return changes;
    }

    // ---- admin ----------------------------------------------------------------------------------

    public synchronized RankedConfig saveConfig(RankedConfig next) {
        if (next == null) throw ApiException.badRequest("缺少排位设置");
        RankedConfig withSeason = next.withSeason(config.get().season());
        List<String> problems = withSeason.problems();
        if (!problems.isEmpty()) throw ApiException.invalid(problems);
        config.save(withSeason);
        return withSeason;
    }

    /** A new season: every score back to the start (the old season's end is kept per player), the season counted up. */
    public synchronized RankedConfig resetSeason() {
        RankedConfig c = config.get();
        Map<String, Integer> positions = positions();
        List<Rating> reset = new ArrayList<>();
        for (Rating r : ratings.findAll()) {
            int position = positions.getOrDefault(r.uuid(), 0);
            Rating.SeasonEnd end = r.matches() == 0 ? null
                    : new Rating.SeasonEnd(c.season(), r.score(), tier(r.score(), position, c).id(), position, r.matches(), r.wins());
            reset.add(r.reset(c.startScore(), end));
        }
        ratings.saveAll(reset);
        RankedConfig next = c.withSeason(c.season() + 1);
        config.save(next);
        return next;
    }

    // ---- rules ------------------------------------------------------------------------------------

    /** 1 = won the pairing, 0.5 = drew it, 0 = lost it: placements decide when both have one, else the outcomes. */
    static double actual(String outcome, int placement, String otherOutcome, int otherPlacement) {
        if (placement > 0 && otherPlacement > 0 && placement != otherPlacement) return placement < otherPlacement ? 1 : 0;
        if (placement > 0 && placement == otherPlacement) return 0.5;
        int a = rank(outcome), b = rank(otherOutcome);
        return a == b ? 0.5 : a > b ? 1 : 0;
    }

    private static int rank(String outcome) {
        return switch (outcome) {
            case "VICTORY" -> 2;
            case "DRAW" -> 1;
            default -> 0;
        };
    }

    static Tier tier(int score, int position, RankedConfig c) {
        if (score >= c.headhunter()) return position > 0 && position <= c.headhunterTop() ? Tier.HEADHUNTER : Tier.DIAMOND;
        if (score >= c.diamond()) return Tier.DIAMOND;
        if (score >= c.gold()) return Tier.GOLD;
        if (score >= c.iron()) return Tier.IRON;
        return Tier.COAL;
    }

    private static Standing standing(Rating rating, int position, RankedConfig c) {
        if (rating == null) return new Standing(c.startScore(), tier(c.startScore(), 0, c).id(), 0, 0, 0);
        return new Standing(rating.score(), tier(rating.score(), position, c).id(), position, rating.matches(), rating.wins());
    }

    /** Placed players, best first. */
    private List<Rating> ladder() {
        return ratings.findAll().stream()
                .filter(r -> r.matches() > 0)
                .sorted(Comparator.comparingInt(Rating::score).reversed()
                        .thenComparing(Rating::reachedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Rating::uuid))
                .toList();
    }

    private Map<String, Integer> positions() {
        Map<String, Integer> out = new HashMap<>();
        List<Rating> ladder = ladder();
        for (int i = 0; i < ladder.size(); i++) out.put(ladder.get(i).uuid(), i + 1);
        return out;
    }

    private String nameOf(String uuid) {
        return players.status(uuid).map(PlayerService.PlayerStatus::name).orElse(null);
    }

    private void pruneMatches() {
        List<RankedMatch> all = matches.findAll();
        if (all.size() <= KEPT_MATCHES) return;
        all.stream().sorted(Comparator.comparing(RankedMatch::at)).limit(all.size() - KEPT_MATCHES)
                .forEach(m -> matches.deleteById(m.matchId()));
    }

    private static String normalize(String uuid) {
        return uuid.toLowerCase(Locale.ROOT);
    }
}
