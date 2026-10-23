package com.blockland.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The last weapon hit each living entity took, so kill feedback (kill streaks, headshot kills, the kill feed) can tell
 * how a kill happened without depending on the weapon modules. Weapon services record a hit right before they apply
 * its damage; a death reads it. Server thread only.
 */
public final class HitRecords {
    /** A hit older than this (game ticks) no longer explains a death. */
    private static final long MAX_AGE = 100;

    /**
     * @param weapon   item id of what dealt it (e.g. {@code blockland:ak47}, {@code blockland:frag_grenade})
     * @param headshot a headshot (or a knife backstab: any hit the weapon counts as critical)
     */
    public record Hit(UUID attacker, String weapon, boolean headshot, long gameTime) {
    }

    private static final Map<LivingEntity, Hit> LAST = new WeakHashMap<>();

    private HitRecords() {
    }

    public static void record(LivingEntity victim, @Nullable Entity attacker, String weapon, boolean headshot) {
        if (attacker == null || victim.level().isClientSide) return;
        LAST.put(victim, new Hit(attacker.getUUID(), weapon, headshot, victim.level().getGameTime()));
    }

    /** The hit that explains the entity's death right now, or null when there is none (fall, void, an old hit). */
    @Nullable
    public static Hit last(LivingEntity victim) {
        Hit hit = LAST.get(victim);
        return hit == null || victim.level().getGameTime() - hit.gameTime() > MAX_AGE ? null : hit;
    }

    public static void clear(LivingEntity victim) {
        LAST.remove(victim);
    }
}
