package com.blockland.server.combat;

import com.blockland.combat.HitRecords;
import com.blockland.network.BLNetwork;
import com.blockland.network.CombatPacketListeners;
import com.blockland.network.packet.CombatPackets;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.MatchBoard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Kill feedback and the Tab board, for every weapon at once. On a death it works out who made the kill and with what
 * (the last weapon hit, {@link HitRecords}: headshot, item), counts kills, deaths and the killer's streak — per match:
 * everything starts over when a player enters or leaves a match — and tells the killer (medals) and the players of the
 * same match (kill feed; outside matches the players in the same world). Players holding the board key get the board
 * of their match (built by the game, {@link GameInstance#board}) twice a second.
 */
public final class KillTracker implements CombatPacketListeners.Server {
    /** Board refresh while held, ticks. */
    private static final int BOARD_INTERVAL = 10;

    private final MinecraftServer server;
    private final GameManager games;
    private final Map<UUID, Score> scores = new HashMap<>();
    private final Set<UUID> watching = new HashSet<>();
    private int ticks;

    /** One player's numbers in one context (a match, or the open world when {@code match} is null). */
    private static final class Score {
        @Nullable
        UUID match;
        int kills, deaths, streak;
    }

    public KillTracker(MinecraftServer server, GameManager games) {
        this.server = server;
        this.games = games;
    }

    // ---- deaths -----------------------------------------------------------------------------------------------------

    /**
     * A living entity died (before any game cancels the death: minigames turn the player into a spectator instead).
     * Call for every death; only kills credited to a player count.
     */
    public void onDeath(LivingEntity victim, DamageSource source) {
        HitRecords.Hit hit = HitRecords.last(victim);
        HitRecords.clear(victim);
        ServerPlayer killer = killerOf(victim, source, hit);
        if (victim instanceof ServerPlayer dead) {
            Score score = score(dead);
            score.deaths++;
            score.streak = 0;
        }
        if (killer == null || killer == victim) {
            if (victim instanceof ServerPlayer dead) feed(dead, null, dead, "", false);
            return;
        }
        String weapon = hit != null && hit.attacker().equals(killer.getUUID()) ? hit.weapon() : weaponOf(killer, source);
        boolean headshot = hit != null && hit.attacker().equals(killer.getUUID()) && hit.headshot();
        Score score = score(killer);
        score.kills++;
        score.streak++;
        boolean player = victim instanceof Player;
        BLNetwork.sendTo(killer, new CombatPackets.S2CKillNotice(name(victim), weapon, headshot, score.streak, player));
        if (player) feed(victim, killer, killer, weapon, headshot);
        else BLNetwork.sendTo(killer, feedLine(killer, victim, weapon, headshot));
    }

    @Nullable
    private ServerPlayer killerOf(LivingEntity victim, DamageSource source, @Nullable HitRecords.Hit hit) {
        if (source.getEntity() instanceof ServerPlayer p) return p;
        if (hit != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(hit.attacker());
            if (p != null) return p;
        }
        return victim.getKillCredit() instanceof ServerPlayer p ? p : null;
    }

    /** Without a recorded hit: what the killer holds, when the death came straight from them. */
    private static String weaponOf(ServerPlayer killer, DamageSource source) {
        if (source.getDirectEntity() != killer) return "";
        ItemStack held = killer.getMainHandItem();
        return held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
    }

    /** Sends a feed line to everyone in the victim's match (or world); {@code anchor} picks the match. */
    private void feed(LivingEntity victim, @Nullable ServerPlayer killer, ServerPlayer anchor, String weapon, boolean headshot) {
        CombatPackets.S2CKillFeed line = feedLine(killer, victim, weapon, headshot);
        for (ServerPlayer p : audience(anchor)) BLNetwork.sendTo(p, line);
    }

    private static CombatPackets.S2CKillFeed feedLine(@Nullable ServerPlayer killer, LivingEntity victim, String weapon, boolean headshot) {
        return new CombatPackets.S2CKillFeed(killer == null ? "" : name(killer), killer == null ? 0xFFFFFF : color(killer),
                name(victim), color(victim), weapon, headshot);
    }

    private List<ServerPlayer> audience(ServerPlayer anchor) {
        GameInstance game = games.gameOf(anchor);
        List<ServerPlayer> out = new ArrayList<>();
        if (game != null) {
            for (UUID id : game.participants()) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) out.add(p);
            }
            return out;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == anchor.level() && games.gameOf(p) == null) out.add(p);
        }
        return out;
    }

    private static String name(Entity entity) {
        return entity instanceof Player p ? p.getGameProfile().getName() : entity.getName().getString();
    }

    private static int color(Entity entity) {
        int color = entity.getTeamColor();
        return color == 0 ? 0xFFFFFF : color & 0xFFFFFF;
    }

    /** The player's numbers in the context they are in now (a fresh score when they changed match). */
    private Score score(ServerPlayer player) {
        GameInstance game = games.gameOf(player);
        UUID match = game == null ? null : game.id();
        Score score = scores.computeIfAbsent(player.getUUID(), id -> new Score());
        if (!Objects.equals(score.match, match)) {
            score.match = match;
            score.kills = score.deaths = score.streak = 0;
        }
        return score;
    }

    /** Numbers of a player for a board of {@code match} (0 when they were counted in another context). */
    private int kills(UUID player, @Nullable UUID match) {
        Score score = scores.get(player);
        return score != null && Objects.equals(score.match, match) ? score.kills : 0;
    }

    private int deaths(UUID player, @Nullable UUID match) {
        Score score = scores.get(player);
        return score != null && Objects.equals(score.match, match) ? score.deaths : 0;
    }

    // ---- the board --------------------------------------------------------------------------------------------------

    @Override
    public void onBoard(ServerPlayer player, boolean open) {
        if (open) {
            watching.add(player.getUUID());
            sendBoard(player);
        } else {
            watching.remove(player.getUUID());
        }
    }

    public void tick() {
        if (++ticks % BOARD_INTERVAL != 0 || watching.isEmpty()) return;
        for (UUID id : new ArrayList<>(watching)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) watching.remove(id);
            else sendBoard(player);
        }
    }

    public void forget(ServerPlayer player) {
        watching.remove(player.getUUID());
        scores.remove(player.getUUID());
    }

    public void clear() {
        watching.clear();
        scores.clear();
    }

    private void sendBoard(ServerPlayer viewer) {
        GameInstance game = games.gameOf(viewer);
        MatchBoard board = game != null ? boardOf(game) : openWorldBoard(viewer);
        BLNetwork.sendTo(viewer, toPacket(board));
    }

    private MatchBoard boardOf(GameInstance game) {
        UUID match = game.id();
        try {
            return game.board(new MatchBoard.Stats() {
                @Override
                public String name(UUID player) {
                    ServerPlayer p = server.getPlayerList().getPlayer(player);
                    return p != null ? p.getGameProfile().getName() : player.toString().substring(0, 8);
                }

                @Override
                public int kills(UUID player) {
                    return KillTracker.this.kills(player, match);
                }

                @Override
                public int deaths(UUID player) {
                    return KillTracker.this.deaths(player, match);
                }
            });
        } catch (RuntimeException e) {
            // a game's board must never break the key: fall back to a plain list
            return new MatchBoard(Component.literal(game.mode().title == null ? "" : game.mode().title), Component.empty(),
                    Component.empty(), List.of(), List.of());
        }
    }

    /** Outside matches: everyone in the viewer's world (not in a match), with the open-world numbers. */
    private MatchBoard openWorldBoard(ServerPlayer viewer) {
        List<MatchBoard.Row> rows = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != viewer.level() || games.gameOf(p) != null) continue;
            rows.add(new MatchBoard.Row(p.getUUID(), p.getGameProfile().getName(), kills(p.getUUID(), null), deaths(p.getUUID(), null),
                    List.of(), null));
        }
        rows.sort(Comparator.comparing(MatchBoard.Row::name, String.CASE_INSENSITIVE_ORDER));
        return new MatchBoard(Component.literal("BlockLand"), Component.translatable("blockland.board.open_world"),
                Component.translatable("blockland.board.online", server.getPlayerCount()), List.of(),
                List.of(new MatchBoard.Team(Component.translatable("blockland.board.players"), 0xFFE8ECF2,
                        Component.translatable("blockland.board.count", rows.size()), rows)));
    }

    private CombatPackets.S2CBoard toPacket(MatchBoard board) {
        List<CombatPackets.S2CBoard.Team> teams = new ArrayList<>();
        for (MatchBoard.Team team : board.teams()) {
            List<CombatPackets.S2CBoard.Row> rows = new ArrayList<>();
            for (MatchBoard.Row row : team.rows()) {
                ServerPlayer p = server.getPlayerList().getPlayer(row.player());
                MatchBoard.State state = row.state() != null ? row.state()
                        : p != null && p.isSpectator() ? MatchBoard.State.SPECTATING : MatchBoard.State.ALIVE;
                rows.add(new CombatPackets.S2CBoard.Row(row.player(), row.name(), row.kills(), row.deaths(), row.values(),
                        p == null ? -1 : Math.max(0, p.connection.latency()), CombatPackets.State.values()[state.ordinal()]));
            }
            teams.add(new CombatPackets.S2CBoard.Team(team.name(), team.color(), team.info(), rows));
        }
        return new CombatPackets.S2CBoard(board.title(), board.subtitle(), board.status(), board.columns(), teams);
    }
}
