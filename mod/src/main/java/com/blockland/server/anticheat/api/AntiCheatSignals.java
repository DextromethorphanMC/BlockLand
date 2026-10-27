package com.blockland.server.anticheat.api;

/**
 * Check ids other modules report through {@link Suspicion} (entries with {@code "signal": true} in
 * {@code data/blockland/anticheat/checks.json}). A new signal needs an id here and an entry there.
 */
public final class AntiCheatSignals {
    /** A gun fired faster than its fire rate allows (the gun module dropped the shot). */
    public static final String FIRE_RATE = "combat.fire_rate";
    /** A shot's direction far from where the player looks (silent aim). */
    public static final String AIM_MISMATCH = "combat.aim_mismatch";
    /** A claimed lag-compensated hit the round never crossed. */
    public static final String HIT_CLAIM = "combat.hit_claim";
    /** A knife attack before its cooldown was over. */
    public static final String MELEE_RATE = "combat.melee_rate";
    /** The parachute module's flight check failed (no sinking under the canopy, too fast sideways, climbing). */
    public static final String PARACHUTE = "movement.parachute";
    /** A vehicle control packet the vehicle module refused (not the driver, values out of range). */
    public static final String VEHICLE_CONTROL = "vehicle.control";
    /** A vehicle weapon fired well before its interval was over (the vehicle module dropped the shot). */
    public static final String VEHICLE_FIRE_RATE = "vehicle.fire_rate";

    private AntiCheatSignals() {
    }
}
