package com.blockland.effect;

import net.minecraft.world.entity.LivingEntity;

/**
 * Whether an entity's hands are locked (no attacking, no using items: left and right click), e.g. while covered
 * in slime. One question for every place that handles an attack or a use — guns, knives and vanilla actions,
 * checked by the server (authoritative) and by the client (instant feedback, no wasted packets).
 */
public final class ActionLocks {
    private ActionLocks() {
    }

    public static boolean isLocked(LivingEntity entity) {
        return BLEffects.SLIMED.isPresent() && entity.hasEffect(BLEffects.slimed());
    }
}
