package com.blockland.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Blasts reaching what is not a body (a vehicle's hull, vehicle spec §6): the modules whose explosions hurt bodies (the
 * frag grenade's blast, the bomb) also tell them here, and whatever soaks a blast its own way implements {@link Target} —
 * neither side knows the other. Server thread only.
 */
public final class Blasts {
    /** Something a blast wears down by its own rules (distance to its own shape, armour). */
    public interface Target {
        /**
         * @param damage what the blast does at its centre (within {@code innerRadius}), falling off to nothing at
         *               {@code radius}
         * @param owner  who set it off (credit), or null
         */
        void takeBlast(ServerLevel level, Vec3 centre, float radius, float innerRadius, float damage, @Nullable LivingEntity owner);
    }

    /** How far beyond its radius a blast still asks targets (their own shape may reach in from there). */
    private static final double REACH = 6;

    private Blasts() {
    }

    /** A blast at {@code centre}: every {@link Target} near it takes it ({@code except}: what went off, if a target). */
    public static void reach(ServerLevel level, Vec3 centre, float radius, float innerRadius, float damage, @Nullable LivingEntity owner,
                             @Nullable Entity except) {
        if (damage <= 0 || radius <= 0) return;
        AABB box = new AABB(centre, centre).inflate(radius + REACH);
        for (Entity e : level.getEntities(except, box, e -> e instanceof Target && e.isAlive())) {
            ((Target) e).takeBlast(level, centre, radius, Math.min(radius, innerRadius), damage, owner);
        }
    }
}
