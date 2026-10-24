package com.blockland.server.ranked;

import com.blockland.BlockLand;
import com.blockland.common.RankTier;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.RankedPackets;
import com.blockland.network.packet.TextJson;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.game.MatchResult;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The ranked ladders (排位) on this server: every mode with the {@code ranked} setting has a ladder of its own, kept by
 * the backend (scores, tiers, positions, the Elo of a result). This service mirrors the standings of the players online
 * in every mode — fetched when they enter the lobby and after each of their ranked matches — for matchmaking by the
 * queued mode's score and the screens ({@link Ranks}), reports every ranked match's result with its mode (players who
 * left before it as defeats) and tells each player what it did to their score. Backend calls run off the server thread;
 * their answers are applied on it. Fails open: without the backend matches are still played, only not counted.
 */
public final class RankedService implements Ranks.View {
    /** The backend's start score: a fair guess for matchmaking until a player's real one arrives. */
    private static final int DEFAULT_SCORE = 1000;
    private static final Type STANDINGS = new TypeToken<AllStandings>() { }.getType();
    private static final Type CHANGES = new TypeToken<List<Change>>() { }.getType();
    private static final Type LADDER = new TypeToken<List<Line>>() { }.getType();

    private final MinecraftServer server;
    private final BackendClient backend;
    private final ContentRepository content;
    private final GameTypeRegistry types;
    /** The match a player is in right now (to tie a published result to its game). */
    private final Function<UUID, GameInstance> gameOf;
    /** Standings by player, then mode (only modes the player has played). */
    private final Map<UUID, Map<String, Ranks.RankInfo>> standings = new ConcurrentHashMap<>();
    /** The standing in a mode not played yet (the backend's start score), null until the backend answered once. */
    @Nullable
    private volatile Ranks.RankInfo fresh;
    /** Players who left a running ranked match, by match id: they are reported as defeats with its result. */
    private final Map<UUID, Set<UUID>> leavers = new HashMap<>();

    // ---- backend JSON (mirrors the backend's ranked API) ---------------------------------------

    static final class Standing {
        int score;
        String tier;
        int position;
        int matches;
        int wins;
    }

    static final class AllStandings {
        Standing fresh;
        Map<String, Map<String, Standing>> players;
    }

    static final class Change {
        String uuid;
        int before;
        int after;
        int delta;
        String tier;
        String tierBefore;
        int position;
    }

    static final class Line {
        String uuid;
        String name;
        int score;
        String tier;
        int position;
    }

    private record PlayersBody(List<String> uuids) {
    }

    private record TeamBody(List<String> players, String outcome, int placement) {
    }

    private record ResultBody(String matchId, String modeId, List<TeamBody> teams) {
    }

    public RankedService(MinecraftServer server, BackendClient backend, ContentRepository content, GameTypeRegistry types,
                         Function<UUID, GameInstance> gameOf) {
        this.server = server;
        this.backend = backend;
        this.content = content;
        this.types = types;
        this.gameOf = gameOf;
    }

    // ---- Ranks.View ---------------------------------------------------------------------------

    @Override
    public Ranks.RankInfo rankOf(UUID player, String modeId) {
        Map<String, Ranks.RankInfo> modes = standings.get(player);
        if (modes == null) return Ranks.RankInfo.UNRANKED;
        Ranks.RankInfo info = modes.get(modeId);
        return info != null ? info : fresh != null ? fresh : Ranks.RankInfo.UNRANKED;
    }

    @Override
    public Map<String, Ranks.RankInfo> ranksOf(UUID player) {
        Map<String, Ranks.RankInfo> modes = standings.get(player);
        return modes == null ? Map.of() : Map.copyOf(modes);
    }

    @Override
    public List<Ranks.RankedMode> rankedModes() {
        List<Ranks.RankedMode> out = new ArrayList<>();
        for (GameModeDef mode : content.snapshot().enabledModes()) {
            if (mode.isOpen() && countsForRank(mode)) out.add(new Ranks.RankedMode(mode.id, mode.title == null || mode.title.isBlank() ? mode.id : mode.title));
        }
        return out;
    }

    @Override
    public CompletableFuture<List<Ranks.Entry>> leaderboard(String modeId, int limit) {
        int n = Math.max(1, Math.min(500, limit));
        String query = "?mode=" + URLEncoder.encode(modeId, StandardCharsets.UTF_8) + "&limit=" + n;
        return backend.<List<Line>>get("/api/server/ranked/leaderboard" + query, LADDER).thenApply(lines -> {
            List<Ranks.Entry> entries = new ArrayList<>();
            if (lines == null) return entries;
            for (Line line : lines) {
                UUID id = parse(line.uuid);
                if (id != null) entries.add(new Ranks.Entry(id, line.name == null ? "?" : line.name, line.score, RankTier.of(line.tier), line.position));
            }
            return entries;
        });
    }

    /** The score matchmaking uses: the player's on the mode's ladder, or the start score for a mode they have not played. */
    public int matchScore(UUID player, String modeId) {
        Map<String, Ranks.RankInfo> modes = standings.get(player);
        Ranks.RankInfo info = modes == null ? null : modes.get(modeId);
        if (info != null) return info.score();
        Ranks.RankInfo start = fresh;
        return start != null ? start.score() : DEFAULT_SCORE;
    }

    // ---- players ------------------------------------------------------------------------------

    /** A player is in the lobby (again): their standing is fetched and sent to them. */
    public void enteredLobby(UUID player) {
        refresh(List.of(player));
    }

    public void forget(UUID player) {
        standings.remove(player);
    }

    public void clear() {
        standings.clear();
        leavers.clear();
    }

    private void refresh(Collection<UUID> players) {
        if (players.isEmpty()) return;
        List<String> ids = players.stream().map(UUID::toString).toList();
        backend.<AllStandings>post("/api/server/ranked/standings", new PlayersBody(ids), STANDINGS)
                .whenComplete((answer, error) -> server.execute(() -> {
                    if (error != null) {
                        BlockLand.LOGGER.debug("[BlockLand] Ranked standings unavailable: {}", error.toString());
                        return;
                    }
                    if (answer == null || answer.players == null) return;
                    if (answer.fresh != null) fresh = info(answer.fresh);
                    answer.players.forEach((key, modes) -> {
                        UUID id = parse(key);
                        if (id == null) return;
                        Map<String, Ranks.RankInfo> infos = new LinkedHashMap<>();
                        if (modes != null) modes.forEach((mode, standing) -> {
                            if (mode != null && standing != null) infos.put(mode, info(standing));
                        });
                        standings.put(id, infos);
                        send(id, infos);
                    });
                }));
    }

    private static Ranks.RankInfo info(Standing standing) {
        return new Ranks.RankInfo(standing.score, RankTier.of(standing.tier), standing.position, standing.matches, standing.wins);
    }

    private void send(UUID player, Map<String, Ranks.RankInfo> modes) {
        Ranks.RankInfo start = fresh != null ? fresh : new Ranks.RankInfo(DEFAULT_SCORE, RankTier.COAL, 0, 0, 0);
        List<RankedPackets.Standing> list = new ArrayList<>();
        modes.forEach((mode, info) -> list.add(standing(mode, info)));
        BLNetwork.sendTo(player, new RankedPackets.S2CRanks(standing("", start), list));
    }

    private static RankedPackets.Standing standing(String mode, Ranks.RankInfo info) {
        return new RankedPackets.Standing(mode, info.score(), info.tier().id(), info.position(), info.matches(), info.wins());
    }

    // ---- results ------------------------------------------------------------------------------

    /** A player left a match before its result (from the game manager). */
    public void abandoned(GameInstance game, UUID player) {
        if (!countsForRank(game.mode())) return;
        leavers.computeIfAbsent(game.id(), id -> new java.util.LinkedHashSet<>()).add(player);
    }

    /** A match published its result (from the result publisher): ranked ones are reported. */
    public void published(MatchResult result) {
        GameModeDef mode = content.snapshot().mode(result.modeId()).orElse(null);
        if (mode == null || !countsForRank(mode) || result.rows().isEmpty()) return;
        GameInstance game = gameOf.apply(result.rows().get(0).player());
        UUID matchId = game != null ? game.id() : UUID.randomUUID();
        Set<UUID> left = game == null ? Set.of() : leavers.getOrDefault(matchId, Set.of());
        leavers.remove(matchId);
        report(matchId, mode, teams(result, left), left);
    }

    /** Whether a mode's matches count: it has the {@code ranked} setting and is a real match (not a drop-in world). */
    private boolean countsForRank(GameModeDef mode) {
        if (!mode.flag("ranked", false)) return false;
        return types.get(mode.type).map(type -> !type.dropIn()).orElse(false);
    }

    /**
     * The result's rows as teams with a placement: rows of the same side (label, colour) and outcome form a team, in the
     * order the game ranked them (best first); winners place 1, a draw places everyone 1, the rest by that order.
     * Leavers come last, each on their own, as defeats.
     */
    private static List<TeamBody> teams(MatchResult result, Set<UUID> left) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, MatchResult.Outcome> outcomes = new HashMap<>();
        for (MatchResult.Row row : result.rows()) {
            String key = TextJson.write(row.team()) + "|" + row.teamColor() + "|" + row.outcome();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row.player().toString());
            outcomes.put(key, row.outcome());
        }
        List<TeamBody> teams = new ArrayList<>();
        int place = 0;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            MatchResult.Outcome outcome = outcomes.get(group.getKey());
            place++;
            int placement = outcome == MatchResult.Outcome.DEFEAT ? place : 1;
            teams.add(new TeamBody(group.getValue(), outcome.name(), placement));
        }
        for (UUID leaver : left) teams.add(new TeamBody(List.of(leaver.toString()), MatchResult.Outcome.DEFEAT.name(), ++place));
        return teams;
    }

    private void report(UUID matchId, GameModeDef mode, List<TeamBody> teams, Set<UUID> left) {
        String title = mode.title == null || mode.title.isBlank() ? mode.id : mode.title;
        backend.<List<Change>>post("/api/server/ranked/results", new ResultBody(matchId.toString(), mode.id, teams), CHANGES)
                .whenComplete((changes, error) -> server.execute(() -> {
                    if (error != null) {
                        BlockLand.LOGGER.warn("[BlockLand] Ranked result of match {} not counted: {}", matchId, error.toString());
                        return;
                    }
                    if (changes == null) return;
                    List<UUID> changed = new ArrayList<>();
                    for (Change change : changes) {
                        UUID id = parse(change.uuid);
                        if (id == null) continue;
                        changed.add(id);
                        BLNetwork.sendTo(id, new RankedPackets.S2CRankChange(title, change.before, change.after,
                                RankTier.of(change.tierBefore).id(), RankTier.of(change.tier).id(), change.position, left.contains(id)));
                    }
                    // positions of everyone moved: the fresh standing (wins, matches) comes from the ladder itself
                    refresh(changed);
                }));
    }

    @Nullable
    private static UUID parse(@Nullable String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
