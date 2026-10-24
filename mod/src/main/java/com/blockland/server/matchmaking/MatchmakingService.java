package com.blockland.server.matchmaking;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameType;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * One queue per game mode. A queue entry is a group that plays on the same team (a solo player or a party);
 * a match pops as soon as the waiting groups can fill every team. Groups with priority (any member owns the
 * {@value #PRIORITY_ENTITLEMENT} entitlement) are placed first, otherwise first come first served. A group may ask
 * for one of the mode's maps: it only plays with groups that want the same map or do not mind. In a ranked mode (排位,
 * the mode's {@code ranked} setting) a match only takes groups whose scores are close to the longest-waiting one's —
 * the window widens the longer it waits — and the teams are balanced by score.
 */
public final class MatchmakingService {
    /** Entitlement (sold in the shop) that puts a player at the front of every queue. */
    public static final String PRIORITY_ENTITLEMENT = "PRIORITY_QUEUE";
    /** Ranked: score difference a fresh group accepts, and how much it grows per second waited (any score after ~90 s). */
    private static final int RANKED_SPREAD = 100;
    private static final int RANKED_SPREAD_PER_SECOND = 10;

    private final PlayerDirectory players;
    private final ContentRepository content;
    private final GameTypeRegistry types;
    private final GameManager games;
    private final Runnable onStateChanged;
    private final Predicate<UUID> hasPriority;
    /** Ranked score of a player (the ladder's, a default for players it does not know yet). */
    private ToIntFunction<UUID> score = id -> 0;
    private final Map<String, List<Entry>> queues = new LinkedHashMap<>();
    private final Map<UUID, Entry> byPlayer = new HashMap<>();
    private int tickCounter;

    public MatchmakingService(PlayerDirectory players, ContentRepository content, GameTypeRegistry types,
                              GameManager games, Runnable onStateChanged, Predicate<UUID> hasPriority) {
        this.players = players;
        this.content = content;
        this.types = types;
        this.games = games;
        this.onStateChanged = onStateChanged;
        this.hasPriority = hasPriority;
    }

    public void setScoreSource(ToIntFunction<UUID> score) {
        this.score = score;
    }

    /** A group waiting together. */
    private static final class Entry {
        final String modeId;
        /** empty = any map */
        final String mapId;
        final List<UUID> members;
        final long since = System.currentTimeMillis();

        Entry(String modeId, String mapId, List<UUID> members) {
            this.modeId = modeId;
            this.mapId = mapId;
            this.members = List.copyOf(members);
        }
    }

    /**
     * Queues a group that must end up on the same team.
     *
     * @param mapId one of the mode's maps, or empty for any
     * @return null on success, otherwise an error message for the requester
     */
    @Nullable
    public Component join(String modeId, String mapId, List<UUID> group) {
        if (group.isEmpty()) return Component.translatable("blockland.queue.nobody");
        for (UUID id : group) {
            if (games.isPlaying(id)) {
                return group.size() == 1 ? Component.translatable("blockland.queue.self_in_game")
                        : Component.translatable("blockland.queue.member_in_game", nameOf(id));
            }
        }
        ContentSnapshot snapshot = content.snapshot();
        Optional<GameModeDef> mode = snapshot.mode(modeId).filter(GameModeDef::isOpen);
        if (mode.isEmpty()) {
            return Component.translatable(snapshot.mode(modeId).filter(GameModeDef::isExpired).isPresent()
                    ? "blockland.queue.mode_ended" : "blockland.queue.mode_closed");
        }
        Optional<GameType> type = types.get(mode.get().type);
        if (type.isEmpty()) return Component.translatable("blockland.queue.type_unsupported", mode.get().type);
        Component unavailable = type.get().checkAvailable(mode.get(), snapshot);
        if (unavailable != null) return unavailable;
        MatchFormat format = type.get().format(mode.get());
        if (group.size() > format.teamSize()) {
            return Component.translatable("blockland.queue.party_too_big", group.size(), format.teamSize());
        }
        String map = mapId == null ? "" : mapId;
        if (!map.isEmpty() && type.get().maps(mode.get(), snapshot).stream().noneMatch(m -> m.id.equals(map))) {
            return Component.translatable("blockland.queue.map_unavailable");
        }

        for (UUID id : group) cancel(id, null);
        if (type.get().dropIn()) {
            // no queue: straight into the running world
            List<MatchRoster.Member> members = new ArrayList<>();
            for (UUID id : group) {
                String name = players.name(id);
                if (name != null) members.add(new MatchRoster.Member(id, name));
            }
            Component error = games.dropIn(type.get(), mode.get(), new MatchRoster(List.of(members), map));
            onStateChanged.run();
            return error;
        }
        Entry entry = new Entry(modeId, map, group);
        queues.computeIfAbsent(modeId, k -> new ArrayList<>()).add(entry);
        for (UUID id : entry.members) byPlayer.put(id, entry);
        onStateChanged.run();
        return null;
    }

    /** Takes the player's whole group out of its queue. */
    public void leave(UUID player, boolean notify) {
        if (cancel(player, null) && notify) onStateChanged.run();
    }

    /**
     * Cancels the queue entries of these players (their party changed), telling the other members why.
     */
    public void cancelFor(Collection<UUID> players, Component reason) {
        boolean changed = false;
        for (UUID id : players) changed |= cancel(id, reason);
        if (changed) onStateChanged.run();
    }

    @Nullable
    public Ticket ticketOf(UUID playerId) {
        Entry entry = byPlayer.get(playerId);
        return entry == null ? null : new Ticket(entry.modeId, entry.mapId, entry.since);
    }

    public int queued(String modeId) {
        List<Entry> queue = queues.get(modeId);
        return queue == null ? 0 : queue.stream().mapToInt(e -> e.members.size()).sum();
    }

    public void tick() {
        if (++tickCounter % 20 != 0) return;
        ContentSnapshot snapshot = content.snapshot();
        for (Map.Entry<String, List<Entry>> queueEntry : new ArrayList<>(queues.entrySet())) {
            String modeId = queueEntry.getKey();
            List<Entry> queue = queueEntry.getValue();
            for (Entry entry : List.copyOf(queue)) {
                if (entry.members.stream().anyMatch(id -> !players.isOnline(id))) {
                    drop(entry, Component.translatable("blockland.queue.member_offline"));
                }
            }
            Optional<GameModeDef> mode = snapshot.mode(modeId).filter(GameModeDef::isOpen);
            Optional<GameType> type = mode.flatMap(m -> types.get(m.type));
            if (mode.isEmpty() || type.isEmpty() || type.get().checkAvailable(mode.get(), snapshot) != null) {
                Component reason = Component.translatable(snapshot.mode(modeId).filter(GameModeDef::isExpired).isPresent()
                        ? "blockland.queue.mode_ended_left" : "blockland.queue.mode_unavailable_left");
                for (Entry entry : List.copyOf(queue)) drop(entry, reason);
                continue;
            }
            MatchFormat format = type.get().format(mode.get());
            boolean ranked = mode.get().flag("ranked", false);
            while (true) {
                Packed packed = ranked ? packRanked(queue, format) : pack(queue, format, true);
                if (packed == null) break;
                startMatch(type.get(), mode.get(), packed);
            }
        }
    }

    /** Admin command: start a match right now with whoever is waiting. */
    @Nullable
    public Component forceStart(String modeId) {
        ContentSnapshot snapshot = content.snapshot();
        Optional<GameModeDef> mode = snapshot.mode(modeId);
        if (mode.isEmpty()) return Component.literal("模式不存在: " + modeId);
        Optional<GameType> type = types.get(mode.get().type);
        if (type.isEmpty()) return Component.literal("未知游戏类型: " + mode.get().type);
        Component unavailable = type.get().checkAvailable(mode.get(), snapshot);
        if (unavailable != null) return unavailable;
        List<Entry> queue = queues.getOrDefault(modeId, new ArrayList<>());
        if (queue.isEmpty()) return Component.literal("队列中没有玩家");
        Packed packed = pack(queue, type.get().format(mode.get()), false);
        if (packed == null) return Component.literal("队列中没有玩家");
        startMatch(type.get(), mode.get(), packed);
        return null;
    }

    public void dropAll() {
        queues.clear();
        byPlayer.clear();
    }

    /** Queue order as matches are formed: priority groups first, each in join order. */
    public List<UUID> order(String modeId) {
        List<UUID> ordered = new ArrayList<>();
        for (Entry entry : ordered(queues.getOrDefault(modeId, List.of()))) ordered.addAll(entry.members);
        return ordered;
    }

    public boolean hasPriority(UUID id) {
        return hasPriority.test(id);
    }

    // ---- internals ----------------------------------------------------------------------------

    private List<Entry> ordered(List<Entry> queue) {
        List<Entry> ordered = new ArrayList<>(queue);
        ordered.sort(Comparator.comparing((Entry e) -> e.members.stream().noneMatch(hasPriority)));
        return ordered;
    }

    private record Packed(List<List<Entry>> teams, String mapId) {
    }

    /**
     * Places waiting groups into teams (best fit, so parties stay together) and removes them from the queue. The first
     * placed group that names a map decides it; groups that want a different one wait for another match.
     *
     * @param full only succeed when every slot is filled
     * @return the teams and map, or null when no match can be formed (the queue is left untouched)
     */
    @Nullable
    private Packed pack(List<Entry> queue, MatchFormat format, boolean full) {
        return pack(queue, ordered(queue), format, full, false);
    }

    /**
     * Ranked: tries the waiting groups in queue order as the anchor of a match and packs only groups whose average score
     * lies within the anchor's window (wider the longer the anchor waited), teams balanced by score.
     */
    @Nullable
    private Packed packRanked(List<Entry> queue, MatchFormat format) {
        List<Entry> ordered = ordered(queue);
        for (Entry anchor : ordered) {
            int centre = averageScore(anchor);
            long waited = (System.currentTimeMillis() - anchor.since) / 1000L;
            long spread = RANKED_SPREAD + RANKED_SPREAD_PER_SECOND * waited;
            List<Entry> pool = new ArrayList<>();
            pool.add(anchor);
            for (Entry entry : ordered) {
                if (entry != anchor && Math.abs(averageScore(entry) - centre) <= spread) pool.add(entry);
            }
            Packed packed = pack(queue, pool, format, true, true);
            if (packed != null) return packed;
        }
        return null;
    }

    private int averageScore(Entry entry) {
        long sum = 0;
        for (UUID id : entry.members) sum += score.applyAsInt(id);
        return entry.members.isEmpty() ? 0 : (int) (sum / entry.members.size());
    }

    /**
     * @param pool    the groups that may play, in the order they are placed
     * @param balance place each group on the team with the lowest score total that has room (ranked), instead of best fit
     */
    @Nullable
    private Packed pack(List<Entry> queue, List<Entry> pool, MatchFormat format, boolean full, boolean balance) {
        List<List<Entry>> teams = new ArrayList<>();
        int[] free = new int[format.teamCount()];
        for (int i = 0; i < format.teamCount(); i++) {
            teams.add(new ArrayList<>());
            free[i] = format.teamSize();
        }
        long[] totals = new long[format.teamCount()];
        int placed = 0;
        String map = "";
        for (Entry entry : pool) {
            if (!entry.mapId.isEmpty() && !map.isEmpty() && !map.equals(entry.mapId)) continue;
            int best = -1;
            for (int i = 0; i < free.length; i++) {
                if (free[i] < entry.members.size()) continue;
                boolean better = best < 0 || (balance ? totals[i] < totals[best] : free[i] < free[best]);
                if (better) best = i;
            }
            if (best < 0) continue;
            if (!entry.mapId.isEmpty()) map = entry.mapId;
            teams.get(best).add(entry);
            free[best] -= entry.members.size();
            if (balance) totals[best] += (long) averageScore(entry) * entry.members.size();
            placed += entry.members.size();
            if (placed == format.requiredPlayers()) break;
        }
        if (placed == 0 || (full && placed < format.requiredPlayers())) return null;
        for (List<Entry> team : teams) {
            for (Entry entry : team) {
                queue.remove(entry);
                entry.members.forEach(byPlayer::remove);
            }
        }
        return new Packed(teams, map);
    }

    private boolean cancel(UUID player, @Nullable Component reason) {
        Entry entry = byPlayer.get(player);
        if (entry == null) return false;
        drop(entry, reason);
        return true;
    }

    private void drop(Entry entry, @Nullable Component reason) {
        List<Entry> queue = queues.get(entry.modeId);
        if (queue != null) queue.remove(entry);
        entry.members.forEach(byPlayer::remove);
        if (reason != null) {
            for (UUID id : entry.members) players.notify(id, LobbyPackets.Level.WARNING, reason);
        }
        onStateChanged.run();
    }

    private void startMatch(GameType type, GameModeDef mode, Packed packed) {
        List<List<MatchRoster.Member>> roster = new ArrayList<>();
        for (List<Entry> team : packed.teams()) {
            List<MatchRoster.Member> members = new ArrayList<>();
            for (Entry entry : team) {
                for (UUID id : entry.members) {
                    String name = players.name(id);
                    if (name != null) members.add(new MatchRoster.Member(id, name));
                }
            }
            roster.add(members);
        }
        MatchRoster matchRoster = new MatchRoster(roster, packed.mapId());
        if (matchRoster.isEmpty()) return;
        games.start(type, mode, matchRoster);
        onStateChanged.run();
    }

    private String nameOf(UUID id) {
        String name = players.name(id);
        return name == null ? id.toString() : name;
    }

    /** @param mapId the map asked for, empty = any */
    public record Ticket(String modeId, String mapId, long since) {
        public int waitedSeconds() {
            return (int) ((System.currentTimeMillis() - since) / 1000L);
        }
    }
}
