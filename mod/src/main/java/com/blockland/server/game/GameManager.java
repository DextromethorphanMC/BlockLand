package com.blockland.server.game;

import com.blockland.BlockLand;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Owns all running matches and routes player events to the match a player belongs to. A match is created while its
 * players still wait in the lobby (the game brings them into the world); when it is over everyone in the world goes
 * back to the lobby.
 */
public final class GameManager {
    /** Every match names its scoreboard teams with this prefix. */
    public static final String MATCH_TEAM_PREFIX = "bl_";

    private final GameServices services;
    private final PlayerDirectory players;
    private final Runnable onStateChanged;
    private final Map<UUID, GameInstance> games = new LinkedHashMap<>();
    private final Map<UUID, GameInstance> byPlayer = new HashMap<>();
    /** Told when a player leaves a match before its result (the ranked ladder counts it as a defeat). */
    private BiConsumer<GameInstance, UUID> onAbandon = (game, player) -> { };

    public GameManager(GameServices services, PlayerDirectory players, Runnable onStateChanged) {
        this.services = services;
        this.players = players;
        this.onStateChanged = onStateChanged;
    }

    public void setAbandonListener(BiConsumer<GameInstance, UUID> listener) {
        this.onAbandon = listener;
    }

    public Optional<GameInstance> start(GameType type, GameModeDef mode, MatchRoster roster) {
        GameInstance game;
        try {
            game = type.create(services, mode, roster);
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to create match for mode {}", mode.id, e);
            roster.players().forEach(p -> players.notify(p.id(), LobbyPackets.Level.ERROR,
                    Component.translatable("blockland.game.create_failed", String.valueOf(e.getMessage()))));
            return Optional.empty();
        }
        games.put(game.id(), game);
        for (MatchRoster.Member member : roster.players()) byPlayer.put(member.id(), game);
        // the loading screen, before the game starts bringing anyone in
        MatchIntro.send(type, mode, game, roster, roster.players().size(), type.dropIn());
        try {
            game.start();
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to start match {}", game.id(), e);
            abort(game);
            return Optional.empty();
        }
        onStateChanged.run();
        return Optional.of(game);
    }

    /**
     * A group joins a drop-in mode: into its running instance when there is one that takes them, otherwise a new one is
     * created for them.
     *
     * @return null when they are in, otherwise why not (for the requester)
     */
    @Nullable
    public Component dropIn(GameType type, GameModeDef mode, MatchRoster roster) {
        for (GameInstance game : games.values()) {
            if (!game.mode().id.equals(mode.id) || game.isFinished() || game.isEnding()) continue;
            if (!game.addPlayers(roster.players())) return Component.translatable("blockland.queue.instance_full");
            for (MatchRoster.Member member : roster.players()) byPlayer.put(member.id(), game);
            MatchIntro.send(type, mode, game, roster, game.participants().size(), true);
            onStateChanged.run();
            return null;
        }
        return start(type, mode, roster).isPresent() ? null : Component.translatable("blockland.game.create_failed", mode.id);
    }

    public void tick() {
        if (games.isEmpty()) return;
        for (GameInstance game : new ArrayList<>(games.values())) {
            try {
                game.tick();
            } catch (Exception e) {
                BlockLand.LOGGER.error("[BlockLand] Match {} crashed, aborting", game.id(), e);
                abort(game);
                continue;
            }
            if (game.isFinished()) finish(game);
        }
    }

    @Nullable
    public GameInstance gameOf(ServerPlayer player) {
        return byPlayer.get(player.getUUID());
    }

    @Nullable
    public GameInstance gameOf(UUID player) {
        return byPlayer.get(player);
    }

    public boolean isPlaying(ServerPlayer player) {
        return byPlayer.containsKey(player.getUUID());
    }

    /** In a match, including one that is still loading while the player waits in the lobby. */
    public boolean isPlaying(UUID player) {
        return byPlayer.containsKey(player);
    }

    public int playingCount(String modeId) {
        int count = 0;
        for (GameInstance game : games.values()) {
            if (game.mode().id.equals(modeId)) count += game.participants().size();
        }
        return count;
    }

    public List<GameInstance> games() {
        return List.copyOf(games.values());
    }

    /** Player voluntarily leaves (or disconnects from) their match; a player in the world goes back to the lobby. */
    public void leave(ServerPlayer player, boolean disconnected) {
        leave(player.getUUID(), player, disconnected);
    }

    /** @param player the entity, null when the player is not in the world (still in the lobby or already gone) */
    public void leave(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        GameInstance game = byPlayer.remove(id);
        if (game == null) return;
        if (!game.isEnding() && !game.isFinished()) onAbandon.accept(game, id);
        game.removePlayer(id, player, disconnected);
        if (!disconnected && player != null) services.players().toLobby(player);
        onStateChanged.run();
    }

    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        GameInstance game = gameOf(player);
        return game != null && game.onPlayerDeath(player, source);
    }

    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        GameInstance game = gameOf(victim);
        return game == null || game.allowDamage(victim, source);
    }

    public float modifyDamage(ServerPlayer victim, DamageSource source, float amount) {
        GameInstance game = gameOf(victim);
        return game == null ? amount : game.modifyDamage(victim, source, amount);
    }

    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        GameInstance game = gameOf(player);
        return game == null || game.allowBlockBreak(player, pos);
    }

    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        GameInstance game = gameOf(player);
        return game == null || game.allowBlockPlace(player, pos);
    }

    /** Whether a participant may slide in their match (false for players outside a match: ask {@link #isPlaying} first). */
    public boolean allowSlide(ServerPlayer player) {
        GameInstance game = gameOf(player);
        return game != null && game.allowSlide(player);
    }

    /** Teams of matches that never cleaned up (the server stopped mid-match) would still tag their players' names. */
    public void removeStaleTeams() {
        Scoreboard scoreboard = services.server().getScoreboard();
        for (PlayerTeam team : List.copyOf(scoreboard.getPlayerTeams())) {
            if (team.getName().startsWith(MATCH_TEAM_PREFIX)) scoreboard.removePlayerTeam(team);
        }
    }

    public void abortAll() {
        for (GameInstance game : new ArrayList<>(games.values())) abort(game);
    }

    public void abort(GameInstance game) {
        for (UUID id : game.participants()) {
            players.notify(id, LobbyPackets.Level.ERROR, Component.translatable("blockland.game.aborted"));
        }
        finish(game);
    }

    private void finish(GameInstance game) {
        games.remove(game.id());
        List<UUID> participants = new ArrayList<>(game.participants());
        byPlayer.values().removeIf(g -> g == game);
        try {
            game.dispose();
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to dispose match {}", game.id(), e);
        }
        // players still waiting in the lobby (the map never finished loading) simply stay there
        for (UUID id : participants) {
            ServerPlayer player = players.inWorld(id);
            if (player != null) services.players().toLobby(player);
        }
        onStateChanged.run();
    }
}
