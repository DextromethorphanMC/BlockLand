package com.blockland.games.practice;

import com.blockland.BlockLand;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Shared flow of the single-player practice modes: the game builds its own course in an arena slot (no map file),
 * brings its one player straight in and keeps them there until they leave — no countdown, no winner, no results panel.
 * The player never really dies: death and falling into the void put them back at the spawn. Concrete practice games
 * build the course and add their drill through the protected hooks.
 */
public abstract class SoloPracticeGame implements GameInstance {
    protected final GameServices services;
    protected final GameModeDef mode;

    private final UUID id = UUID.randomUUID();
    private final UUID playerId;
    private final Vec3i courseSize;
    private Arena arena;
    private boolean present = true;
    private boolean arrived;
    private boolean finished;
    private boolean disposed;
    private int playingTicks;

    protected SoloPracticeGame(GameServices services, GameModeDef mode, MatchRoster roster, Vec3i courseSize) {
        List<MatchRoster.Member> players = roster.players();
        if (players.isEmpty()) throw new IllegalArgumentException("practice needs a player");
        this.services = services;
        this.mode = mode;
        this.playerId = players.get(0).id();
        this.courseSize = courseSize;
    }

    // ---- hooks -----------------------------------------------------------------------------

    /** Shown in the intro line, e.g. "搭路练习". */
    protected abstract Component gameName();

    /** Places the course (server thread, once, before the player arrives). */
    protected abstract void build(Arena arena);

    /** Where the player stands at the start and after every fall (world position, see {@link Arena#toWorld}). */
    protected abstract Vec3 spawnPoint();

    protected float spawnYaw() {
        return 0;
    }

    /** The player arrived in the course (already reset and at the spawn): hand out what the drill needs. */
    protected void onPlayerReady(ServerPlayer player) {
    }

    /** The player was put back at the spawn after a fall or a death. */
    protected void onRespawned(ServerPlayer player) {
    }

    /** Every tick while the player is in the course. */
    protected void onPlayingTick(ServerPlayer player) {
    }

    /** Removes what the game spawned (the arena blocks are wiped by the arena service). */
    protected void onDispose() {
    }

    protected GameType playingGameMode() {
        return GameType.ADVENTURE;
    }

    /** Blocks below the arena origin that count as the void. */
    protected int voidDepth() {
        return 12;
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Override
    public void start() {
        arena = services.arenas().allocate(courseSize);
        build(arena);
        services.players().bring(playerId, this::arrive);
    }

    private void arrive(ServerPlayer player) {
        if (disposed || finished || !present) {
            services.players().toLobby(player);
            return;
        }
        arrived = true;
        PlayerUtil.reset(player, playingGameMode());
        PlayerUtil.teleport(player, arena.level(), spawnPoint(), spawnYaw(), 0);
        player.sendSystemMessage(Component.empty()
                .append(gameName().copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(mode.title).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("blockland.practice.leave_hint").withStyle(ChatFormatting.GRAY)));
        onPlayerReady(player);
    }

    @Override
    public void tick() {
        if (finished || !arrived) return;
        ServerPlayer player = services.server().getPlayerList().getPlayer(playerId);
        if (player == null) return;
        if (player.level() != arena.level()) {
            // something took the player out of the course (a command): end the practice instead of fighting it
            finished = true;
            return;
        }
        playingTicks++;
        if (player.getY() < arena.origin().getY() - voidDepth()) {
            respawn(player);
            onRespawned(player);
            return;
        }
        onPlayingTick(player);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        if (!id.equals(playerId)) return;
        present = false;
        finished = true;
    }

    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        if (!player.getUUID().equals(playerId)) return false;
        respawn(player);
        onRespawned(player);
        return true;
    }

    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        return false;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return false;
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return false;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        finished = true;
        try {
            onDispose();
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Practice cleanup failed", e);
        }
        if (arena != null) services.arenas().release(arena);
    }

    // ---- players -------------------------------------------------------------------------

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public GameModeDef mode() {
        return mode;
    }

    @Override
    public Collection<UUID> participants() {
        return present ? List.of(playerId) : List.of();
    }

    // ---- helpers for subclasses ----------------------------------------------------------

    protected Arena arena() {
        return arena;
    }

    protected UUID playerId() {
        return playerId;
    }

    /** The player when they are online and in the course. */
    @Nullable
    protected ServerPlayer player() {
        ServerPlayer player = services.server().getPlayerList().getPlayer(playerId);
        return player != null && arena != null && player.level() == arena.level() ? player : null;
    }

    /** Ticks the player has spent in the course. */
    protected int playingTicks() {
        return playingTicks;
    }

    /** Back to the spawn with full health and no fall damage pending. */
    protected void respawn(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.clearFire();
        player.removeAllEffects();
        PlayerUtil.teleport(player, arena.level(), spawnPoint(), spawnYaw(), 0);
    }
}
