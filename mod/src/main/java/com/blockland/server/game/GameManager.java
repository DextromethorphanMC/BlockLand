package com.blockland.server.game;

import com.blockland.BlockLand;
import com.blockland.server.content.model.GameModeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Owns all running matches and routes player events to the match a player belongs to. */
public final class GameManager {
    private final GameServices services;
    private final Consumer<ServerPlayer> returnToLobby;
    private final Runnable onStateChanged;
    private final Map<UUID, GameInstance> games = new LinkedHashMap<>();
    private final Map<UUID, GameInstance> byPlayer = new HashMap<>();

    public GameManager(GameServices services, Consumer<ServerPlayer> returnToLobby, Runnable onStateChanged) {
        this.services = services;
        this.returnToLobby = returnToLobby;
        this.onStateChanged = onStateChanged;
    }

    public Optional<GameInstance> start(GameType type, GameModeDef mode, MatchRoster roster) {
        List<ServerPlayer> players = roster.players();
        GameInstance game;
        try {
            game = type.create(services, mode, roster);
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to create match for mode {}", mode.id, e);
            players.forEach(p -> p.sendSystemMessage(Component.literal("§c无法创建对局: " + e.getMessage())));
            return Optional.empty();
        }
        games.put(game.id(), game);
        for (ServerPlayer player : players) byPlayer.put(player.getUUID(), game);
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

    public boolean isPlaying(ServerPlayer player) {
        return byPlayer.containsKey(player.getUUID());
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

    /** Player voluntarily leaves (or disconnects from) their match. */
    public void leave(ServerPlayer player, boolean disconnected) {
        GameInstance game = byPlayer.remove(player.getUUID());
        if (game == null) return;
        game.removePlayer(player, disconnected);
        if (!disconnected) returnToLobby.accept(player);
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

    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        GameInstance game = gameOf(player);
        return game == null || game.allowBlockBreak(player, pos);
    }

    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        GameInstance game = gameOf(player);
        return game == null || game.allowBlockPlace(player, pos);
    }

    public void abortAll() {
        for (GameInstance game : new ArrayList<>(games.values())) abort(game);
    }

    public void abort(GameInstance game) {
        for (UUID id : game.participants()) {
            ServerPlayer player = services.server().getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(Component.literal("§c对局已被中止"));
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
        for (UUID id : participants) {
            ServerPlayer player = services.server().getPlayerList().getPlayer(id);
            if (player != null) returnToLobby.accept(player);
        }
        onStateChanged.run();
    }
}
