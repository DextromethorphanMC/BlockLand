package com.blockland.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.Level;

/**
 * 恶魂领主: a ghast shrunk to a player's size, drawn with the vanilla ghast model and textures. Infected players are
 * drawn as it (morph), so it never needs to spawn naturally. When its player fires a fireball the client calls
 * {@link #shot} on the morph proxy, and the renderer opens its mouth (the ghast's shooting face) for a moment.
 */
public class GhastLord extends Ghast {
    /** Seconds the mouth stays open after a shot. */
    private static final float MOUTH_SECONDS = 0.6f;
    /** Client only: when it last fired (the client's render clock in nanoseconds, 0 = never). */
    private long shotAt;

    public GhastLord(EntityType<? extends GhastLord> type, Level level) {
        super(type, level);
    }

    /** Client: it fired at {@code nanos}. */
    public void shot(long nanos) {
        shotAt = nanos;
    }

    /** Client: whether it shows its shooting face at {@code now} (a real ghast charging, or a shot just fired). */
    public boolean mouthOpen(long now) {
        return isCharging() || shotAt != 0 && now - shotAt < (long) (MOUTH_SECONDS * 1e9);
    }
}
