package com.blockland.server.game;

import com.blockland.server.content.model.GameModeDef;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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

    /** The result is in and the match only waits before sending everyone back (the results panel is showing). */
    default boolean isEnding() {
        return false;
    }

    /**
     * The player leaves the match (disconnect, /bl leave or the results panel). They must no longer be in
     * {@link #participants()} afterwards.
     *
     * @param player the entity, or null when the player never left the lobby (or is already gone)
     */
    void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected);

    /** @return true to cancel the vanilla death (the game handles it itself). */
    boolean onPlayerDeath(ServerPlayer player, DamageSource source);

    /** @return false to cancel the damage. */
    default boolean allowDamage(ServerPlayer victim, DamageSource source) {
        return true;
    }

    /** Final say over damage a participant takes (after {@link #allowDamage} let it through). */
    default float modifyDamage(ServerPlayer victim, DamageSource source, float amount) {
        return amount;
    }

    /** @return false to prevent a participant from breaking a block. */
    default boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return true;
    }

    /** @return false to prevent a participant from placing a block. */
    default boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return true;
    }

    /**
     * Whether a participant may slide: the mode's {@code allowSlide} setting (backend, off when missing); a type may
     * narrow it further (e.g. by role).
     */
    default boolean allowSlide(ServerPlayer player) {
        return mode().flag("allowSlide", false);
    }

    /** Whether a participant's guns fire and reload without using ammo (practice modes). */
    default boolean infiniteAmmo(ServerPlayer player) {
        return false;
    }

    /** Whether a participant's equipment (grenades, throwing knives, injectors) is not used up (practice modes). */
    default boolean infiniteEquipment(ServerPlayer player) {
        return false;
    }

    /**
     * The Tab board of this match. By default one list of every participant with the kill tracker's kills and deaths,
     * best first; types override it to show their sides, columns and state.
     */
    default MatchBoard board(MatchBoard.Stats stats) {
        List<MatchBoard.Row> rows = participants().stream()
                .map(id -> new MatchBoard.Row(id, stats.name(id), stats.kills(id), stats.deaths(id), List.of(), null))
                .sorted(Comparator.comparingInt((MatchBoard.Row r) -> -r.kills()).thenComparingInt(MatchBoard.Row::deaths))
                .toList();
        return new MatchBoard(Component.literal(mode().title == null ? "" : mode().title),
                Component.empty(), Component.empty(), List.of(),
                List.of(new MatchBoard.Team(Component.translatable("blockland.board.players"),
                        0xFFE8ECF2, Component.empty(), rows)));
    }

    /** Releases world resources. Called once, after the match finished or when it is aborted. */
    void dispose();
}
