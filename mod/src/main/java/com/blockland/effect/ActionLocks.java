package com.blockland.effect;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Whether an entity's hands are locked (no attacking, no using items: left and right click), e.g. while covered
 * in slime. One question for every place that handles an attack or a use — guns, knives and vanilla actions,
 * checked by the server (authoritative) and by the client (instant feedback, no wasted packets). Other modules add
 * their own reasons with {@link #addLock} (a rider inside a vehicle's hull) without this class knowing them.
 */
public final class ActionLocks {
    private static final List<Predicate<LivingEntity>> locks = new CopyOnWriteArrayList<>();

    private ActionLocks() {
    }

    /** Another reason hands are locked (both sides; registered once at start-up). */
    public static void addLock(Predicate<LivingEntity> lock) {
        locks.add(lock);
    }

    public static boolean isLocked(LivingEntity entity) {
        if (BLEffects.SLIMED.isPresent() && entity.hasEffect(BLEffects.slimed())) return true;
        for (Predicate<LivingEntity> lock : locks) if (lock.test(entity)) return true;
        return false;
    }
}
