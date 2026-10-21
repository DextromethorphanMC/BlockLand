package com.blockland.server.game;

import com.blockland.server.content.model.GameModeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.util.Collection;
import java.util.UUID;

/**
 * One running match. Driven by {@link GameManager} on the server thread; the manager routes
 * player events to the instance the player belongs to and returns players to the lobby afterwards.
 */
public interface GameInstance {

    UUID id();

    GameModeDef mode();

    /** Every player still attached to this match (alive or spectating). */
    Collection<UUID> participants();

    void start();

    void tick();

    boolean isFinished();

    /** The player leaves the match (disconnect or /bl leave). They must no longer be in {@link #participants()}. */
    void removePlayer(ServerPlayer player, boolean disconnected);

    /** @return true to cancel the vanilla death (the game handles it itself). */
    boolean onPlayerDeath(ServerPlayer player, DamageSource source);

    /** @return false to cancel the damage. */
    default boolean allowDamage(ServerPlayer victim, DamageSource source) {
        return true;
    }

    /** @return false to prevent a participant from breaking a block. */
    default boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return true;
    }

    /** @return false to prevent a participant from placing a block. */
    default boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return true;
    }

    /** Releases world resources. Called once, after the match finished or when it is aborted. */
    void dispose();
}
