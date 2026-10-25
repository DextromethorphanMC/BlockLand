package com.blockland.server.anticheat.api;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Where other modules tell the anti-cheat that a player asked for something impossible (anti-cheat spec §11): a gun
 * fired faster than its rate, a knife swung before its cooldown, a parachute flight that failed its check. The module
 * keeps refusing the request itself; this only adds the evidence — the anti-cheat scores it per the player's mode
 * (violation level, confidence, kicks, bans). Ids are {@link AntiCheatSignals}. Without an anti-cheat listening (it is
 * set up by the server's composition root) a report does nothing and builds no detail text.
 */
public final class Suspicion {
    /** What receives the reports (the anti-cheat service). */
    public interface Sink {
        /**
         * @param amount how much this counts: 1 = one clear violation (the check's VL rises by about that much)
         * @param detail what happened, for the evidence (Chinese like the admin UI, numbers included)
         */
        void report(ServerPlayer player, String check, double amount, String detail);
    }

    private static final Sink NOOP = (player, check, amount, detail) -> { };
    private static volatile Sink sink = NOOP;

    private Suspicion() {
    }

    public static void setSink(@Nullable Sink next) {
        sink = next == null ? NOOP : next;
    }

    public static void report(ServerPlayer player, String check, double amount, String detail) {
        sink.report(player, check, amount, detail);
    }

    /** Like {@link #report(ServerPlayer, String, double, String)}, formatting the detail only when someone listens. */
    public static void report(ServerPlayer player, String check, double amount, Supplier<String> detail) {
        Sink current = sink;
        if (current != NOOP) current.report(player, check, amount, detail.get());
    }
}
