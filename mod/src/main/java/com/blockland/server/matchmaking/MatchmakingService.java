package com.blockland.server.matchmaking;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameType;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

/**
 * One queue per game mode. A queue entry is a group that plays on the same team (a solo player or a party);
 * a match pops as soon as the waiting groups can fill every team. Groups with priority (any member owns the
 * {@value #PRIORITY_ENTITLEMENT} entitlement) are placed first, otherwise first come first served.
 */
public final class MatchmakingService {
    /** Entitlement (sold in the shop) that puts a player at the front of every queue. */
    public static final String PRIORITY_ENTITLEMENT = "PRIORITY_QUEUE";

    private final MinecraftServer server;
    private final ContentRepository content;
    private final GameTypeRegistry types;
    private final GameManager games;
    private final Runnable onStateChanged;
    private final Predicate<UUID> hasPriority;
    private final Map<String, List<Entry>> queues = new LinkedHashMap<>();
    private final Map<UUID, Entry> byPlayer = new HashMap<>();
    private int tickCounter;

    public MatchmakingService(MinecraftServer server, ContentRepository content, GameTypeRegistry types,
                              GameManager games, Runnable onStateChanged, Predicate<UUID> hasPriority) {
        this.server = server;
        this.content = content;
        this.types = types;
        this.games = games;
        this.onStateChanged = onStateChanged;
        this.hasPriority = hasPriority;
    }

    /** A group waiting together. */
    private static final class Entry {
        final String modeId;
        final List<UUID> members;
        final long since = System.currentTimeMillis();

        Entry(String modeId, List<UUID> members) {
            this.modeId = modeId;
            this.members = List.copyOf(members);
        }
    }

    /**
     * Queues a group that must end up on the same team.
     *
     * @return null on success, otherwise an error message for the requester
     */
    @Nullable
    public String join(String modeId, List<ServerPlayer> group) {
        if (group.isEmpty()) return "没有可以匹配的玩家";
        for (ServerPlayer player : group) {
            if (games.isPlaying(player)) {
                return group.size() == 1 ? "你已经在对局中" : "队员 " + player.getGameProfile().getName() + " 正在对局中";
            }
        }
        ContentSnapshot snapshot = content.snapshot();
        Optional<GameModeDef> mode = snapshot.mode(modeId).filter(GameModeDef::isOpen);
        if (mode.isEmpty()) {
            return snapshot.mode(modeId).filter(GameModeDef::isExpired).isPresent() ? "该限时模式已结束" : "该模式不存在或已关闭";
        }
        Optional<GameType> type = types.get(mode.get().type);
        if (type.isEmpty()) return "服务器不支持该游戏类型: " + mode.get().type;
        String unavailable = type.get().checkAvailable(mode.get(), snapshot);
        if (unavailable != null) return unavailable;
        MatchFormat format = type.get().format(mode.get());
        if (group.size() > format.teamSize()) {
            return "队伍有 " + group.size() + " 人，超过该模式每队 " + format.teamSize() + " 人的上限";
        }

        for (ServerPlayer player : group) cancel(player.getUUID(), null);
        Entry entry = new Entry(modeId, group.stream().map(ServerPlayer::getUUID).toList());
        queues.computeIfAbsent(modeId, k -> new ArrayList<>()).add(entry);
        for (UUID id : entry.members) byPlayer.put(id, entry);
        onStateChanged.run();
        return null;
    }

    /** Takes the player's whole group out of its queue. */
    public void leave(ServerPlayer player, boolean notify) {
        if (cancel(player.getUUID(), null) && notify) onStateChanged.run();
    }

    /**
     * Cancels the queue entries of these players (their party changed), telling the other members why.
     */
    public void cancelFor(Collection<UUID> players, String reason) {
        boolean changed = false;
        for (UUID id : players) changed |= cancel(id, reason);
        if (changed) onStateChanged.run();
    }

    @Nullable
    public Ticket ticketOf(UUID playerId) {
        Entry entry = byPlayer.get(playerId);
        return entry == null ? null : new Ticket(entry.modeId, entry.since);
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
                if (entry.members.stream().anyMatch(id -> server.getPlayerList().getPlayer(id) == null)) {
                    drop(entry, "队员离线，已取消匹配");
                }
            }
            Optional<GameModeDef> mode = snapshot.mode(modeId).filter(GameModeDef::isOpen);
            Optional<GameType> type = mode.flatMap(m -> types.get(m.type));
            if (mode.isEmpty() || type.isEmpty() || type.get().checkAvailable(mode.get(), snapshot) != null) {
                String reason = snapshot.mode(modeId).filter(GameModeDef::isExpired).isPresent()
                        ? "限时模式已结束，已退出匹配" : "该模式当前不可用，已退出匹配";
                for (Entry entry : List.copyOf(queue)) drop(entry, reason);
                continue;
            }
            MatchFormat format = type.get().format(mode.get());
            while (true) {
                List<List<Entry>> teams = pack(queue, format, true);
                if (teams == null) break;
                startMatch(type.get(), mode.get(), teams);
            }
        }
    }

    /** Admin command: start a match right now with whoever is waiting. */
    @Nullable
    public String forceStart(String modeId) {
        ContentSnapshot snapshot = content.snapshot();
        Optional<GameModeDef> mode = snapshot.mode(modeId);
        if (mode.isEmpty()) return "模式不存在: " + modeId;
        Optional<GameType> type = types.get(mode.get().type);
        if (type.isEmpty()) return "未知游戏类型: " + mode.get().type;
        String unavailable = type.get().checkAvailable(mode.get(), snapshot);
        if (unavailable != null) return unavailable;
        List<Entry> queue = queues.getOrDefault(modeId, new ArrayList<>());
        if (queue.isEmpty()) return "队列中没有玩家";
        List<List<Entry>> teams = pack(queue, type.get().format(mode.get()), false);
        if (teams == null) return "队列中没有玩家";
        startMatch(type.get(), mode.get(), teams);
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

    /**
     * Places waiting groups into teams (best fit, so parties stay together) and removes them from the queue.
     *
     * @param full only succeed when every slot is filled
     * @return the teams, or null when no match can be formed (the queue is left untouched)
     */
    @Nullable
    private List<List<Entry>> pack(List<Entry> queue, MatchFormat format, boolean full) {
        List<List<Entry>> teams = new ArrayList<>();
        int[] free = new int[format.teamCount()];
        for (int i = 0; i < format.teamCount(); i++) {
            teams.add(new ArrayList<>());
            free[i] = format.teamSize();
        }
        int placed = 0;
        for (Entry entry : ordered(queue)) {
            int best = -1;
            for (int i = 0; i < free.length; i++) {
                if (free[i] >= entry.members.size() && (best < 0 || free[i] < free[best])) best = i;
            }
            if (best < 0) continue;
            teams.get(best).add(entry);
            free[best] -= entry.members.size();
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
        return teams;
    }

    private boolean cancel(UUID player, @Nullable String reason) {
        Entry entry = byPlayer.get(player);
        if (entry == null) return false;
        drop(entry, reason);
        return true;
    }

    private void drop(Entry entry, @Nullable String reason) {
        List<Entry> queue = queues.get(entry.modeId);
        if (queue != null) queue.remove(entry);
        entry.members.forEach(byPlayer::remove);
        if (reason != null) {
            for (UUID id : entry.members) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) player.sendSystemMessage(Component.literal("§e" + reason));
            }
        }
        onStateChanged.run();
    }

    private void startMatch(GameType type, GameModeDef mode, List<List<Entry>> teams) {
        List<List<ServerPlayer>> roster = new ArrayList<>();
        for (List<Entry> team : teams) {
            List<ServerPlayer> players = new ArrayList<>();
            for (Entry entry : team) {
                for (UUID id : entry.members) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) players.add(player);
                }
            }
            roster.add(players);
        }
        MatchRoster matchRoster = new MatchRoster(roster);
        if (matchRoster.isEmpty()) return;
        for (ServerPlayer player : matchRoster.players()) BLNetwork.sendTo(player, new S2CMatchFound(mode.title));
        games.start(type, mode, matchRoster);
        onStateChanged.run();
    }

    public record Ticket(String modeId, long since) {
        public int waitedSeconds() {
            return (int) ((System.currentTimeMillis() - since) / 1000L);
        }
    }
}
