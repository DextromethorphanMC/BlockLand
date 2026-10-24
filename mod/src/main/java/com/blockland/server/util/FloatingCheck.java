package com.blockland.server.util;

import com.blockland.BlockLand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Vanilla's anti-fly kick ("Flying is not enabled on this server") counts every tick a player spends in open air —
 * it ignores how they move (its vertical test is always true) — and kicks after about four seconds. Legitimate long
 * air time (a sky-dive under a parachute) must be judged by a check that does look at the motion; once that check has
 * accepted this tick, {@link #excuse} resets vanilla's counter. Never excuse a player the check has not validated:
 * vanilla's kick is the safety net for everything else.
 */
public final class FloatingCheck {
    @Nullable
    private static final Field ABOVE_GROUND = find();

    private FloatingCheck() {
    }

    /** Vanilla's floating counter starts again from zero for this player. */
    public static void excuse(ServerPlayer player) {
        if (ABOVE_GROUND == null || player.connection == null) return;
        try {
            ABOVE_GROUND.setInt(player.connection, 0);
        } catch (IllegalAccessException ignored) {
            // found and made accessible once; cannot happen afterwards
        }
    }

    @Nullable
    private static Field find() {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField("aboveGroundTickCount");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Vanilla's floating counter not found: long parachute descents will be kicked", e);
            return null;
        }
    }
}
