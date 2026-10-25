package com.blockland.server.anticheat.check;

import com.blockland.server.anticheat.rules.RuleSet;
import com.blockland.server.anticheat.track.PlayerTrack;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What a check hands its violations to (the evidence and enforcement layers behind {@code AntiCheatService}): it scores
 * them per the player's rules and says what to do. Checks never kick, ban or report on their own.
 */
public interface Judge {
    /** What the caller does about a violation. */
    enum Outcome {
        /** Nothing (yet): only the violation level rose. */
        NONE,
        /** Undo it: set the player back, drop the packet, cancel the attack. */
        MITIGATE,
        /** The player was kicked: stop handling them. */
        KICKED;

        public Outcome max(Outcome other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    RuleSet rules(UUID player);

    /**
     * Scores a violation.
     *
     * @param player the entity when the player is in the world, null in the lobby
     * @param amount violation level to add (about 1 for one clear violation, at most ~5)
     * @param detail what happened, for the evidence (Chinese, with numbers)
     */
    Outcome flag(UUID id, @Nullable ServerPlayer player, PlayerTrack track, int check, double amount, String detail);

    /** The player's latency window in ticks (anti-cheat spec §5). */
    int window(ServerPlayer player, PlayerTrack track, RuleSet rules);

    /** The player's smoothed round trip in milliseconds. */
    double rtt(ServerPlayer player, PlayerTrack track);
}
