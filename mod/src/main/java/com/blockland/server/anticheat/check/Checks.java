package com.blockland.server.anticheat.check;

import com.blockland.server.anticheat.api.AntiCheatSignals;

/**
 * The anti-cheat's own check ids and their catalogue indices (resolved once from {@link CheckCatalog}; -1 when the data
 * lacks one, and such a check never fires). Signals other modules report are in {@link AntiCheatSignals}.
 */
public final class Checks {
    public static final int PACKET_RATE = index("packet.rate");
    public static final int PACKET_INVALID = index("packet.invalid");
    public static final int TRANSACTION = index("packet.transaction");
    public static final int TIMER = index("packet.timer");
    public static final int SPEED = index("movement.speed");
    public static final int VERTICAL = index("movement.vertical");
    public static final int GROUND = index("movement.ground");
    public static final int VELOCITY = index("movement.velocity");
    public static final int REACH = index("combat.reach");
    public static final int ATTACK_ANGLE = index("combat.attack_angle");
    public static final int MULTI_TARGET = index("combat.multi_target");
    public static final int CPS = index("combat.cps");
    public static final int PLACE_FACE = index("world.place_face");

    private Checks() {
    }

    private static int index(String id) {
        return CheckCatalog.get().index(id);
    }
}
