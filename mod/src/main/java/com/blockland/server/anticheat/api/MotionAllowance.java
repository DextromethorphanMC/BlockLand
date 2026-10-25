package com.blockland.server.anticheat.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * A movement mechanic of a module that vanilla physics does not know (anti-cheat spec §6, §11): the slide's burst of
 * speed, a dive's launch, a parachute's glide. Asked once per judged move; the module fills in what its mechanic allows
 * right now and leaves the rest alone. Velocities the server itself gives a player ({@code setDeltaMovement} +
 * {@code hurtMarked}, explosions) are captured by the anti-cheat on its own and need no allowance.
 */
@FunctionalInterface
public interface MotionAllowance {
    /**
     * @param windowTicks the player's latency window: a state that changed within this many ticks may not have reached
     *                    their client yet (or their client may already act on one the server has not seen), so allow
     *                    both
     */
    void allow(ServerPlayer player, MotionBudget budget, int windowTicks);
}
