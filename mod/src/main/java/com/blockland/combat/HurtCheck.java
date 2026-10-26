package com.blockland.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Whether a hit really hurt its target — the server's word that decides whether a hit on a body bleeds (rendering spec
 * §12): {@code hurtServer} returning true is not enough, since body armour can soak a round whole and a game can turn the
 * damage down to nothing. A weapon notes {@link #vitality} before the hurt and asks {@link #hurt} after it.
 */
public final class HurtCheck {
    private HurtCheck() {
    }

    /** What the target has to lose: health and absorption of a living one, 0 of anything else. */
    public static float vitality(Entity target) {
        return target instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 0;
    }

    /**
     * After a hurt that went through: whether it took anything off the target ({@code before} = {@link #vitality} before it)
     * or killed it. Anything not living that took the hurt counts as hurt.
     */
    public static boolean hurt(Entity target, float before, boolean killed) {
        if (killed || !(target instanceof LivingEntity)) return true;
        return before - vitality(target) > 1e-4f;
    }
}
