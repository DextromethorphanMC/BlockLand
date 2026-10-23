package com.blockland.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.combat.HitRecords;
import com.blockland.common.Sounds;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Molotov: the bottle breaks and the fire floods the floor around it ({@link FireSpread}), spreading out from the
 * impact over {@code spreadTime}. Whoever stands in a burning cell takes {@code damagePerSecond} and catches fire.
 * Smoke puts it out; a bottle breaking inside smoke fizzles.
 */
final class Molotov {
    /** Flames reach this high over their floor (blocks). */
    private static final double FLAME_HEIGHT = 1.1;

    private Molotov() {
    }

    static int ignite(ServerLevel level, Entity thrown, JsonObject params, @Nullable LivingEntity owner) {
        Vec3 at = thrown.position();
        float radius = Params.f(params, "radius", 3.6f);
        level.playSound(null, at.x, at.y, at.z, Sounds.byId(BlockLand.id("equipment.molotov_shatter")), SoundSource.PLAYERS, 1.4f,
                0.95f + level.random.nextFloat() * 0.1f);
        if (Areas.smokeAt(level, at, radius * 0.3f) != null) {
            fizzle(level, at);
            return 0;
        }
        List<FireSpread.Cell> cells = FireSpread.cells(level, at, radius, Params.i(params, "maxFall", 6));
        if (cells.isEmpty()) {
            fizzle(level, at);
            return 0;
        }
        FireSpread.Cell first = cells.get(0);
        Vec3 centre = new Vec3(first.centreX(), first.floorY(), first.centreZ());
        Areas.addFire(new Areas.Fire(thrown, cells, centre, radius, level.getGameTime(), Params.f(params, "spreadTime", 0.6f)));
        return Math.max(20, Math.round(Params.f(params, "duration", 7) * 20));
    }

    static void tick(ServerLevel level, Entity thrown, JsonObject params, @Nullable LivingEntity owner, int ticksLeft) {
        Areas.Fire fire = Areas.fire(thrown);
        if (fire == null) return;
        long now = level.getGameTime();
        if (ticksLeft <= 1) {
            Areas.removeFire(thrown);
            return;
        }
        if (now % 5 == 0 && Areas.smokeAt(level, fire.centre(), fire.radius() * 0.5f) != null) {
            extinguish(level, thrown);
            return;
        }
        if ((now - fire.startTick()) % 10 != 0) return;
        float damage = Params.f(params, "damagePerSecond", 4) / 2;
        int ignite = Math.round(Params.f(params, "igniteSeconds", 2) * 20);
        AABB around = new AABB(fire.centre(), fire.centre()).inflate(fire.radius() + 1.5, 3, fire.radius() + 1.5);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, around, e -> e.isAlive() && !e.isSpectator())) {
            if (!standsInFire(victim.getBoundingBox(), fire, now)) continue;
            HitRecords.record(victim, owner, EffectDamage.MOLOTOV_ITEM, false);
            victim.invulnerableTime = 0;
            victim.hurtServer(level, EffectDamage.source(level, EffectDamage.MOLOTOV, thrown, owner), damage);
            if (!victim.isInWaterOrRain() && victim.getRemainingFireTicks() < ignite) victim.setRemainingFireTicks(ignite);
        }
    }

    private static boolean standsInFire(AABB body, Areas.Fire fire, long now) {
        for (FireSpread.Cell cell : fire.cells()) {
            if (!fire.lit(cell, now)) continue;
            if (body.intersects(cell.x() + 0.05, cell.floorY(), cell.z() + 0.05, cell.x() + 0.95, cell.floorY() + FLAME_HEIGHT, cell.z() + 0.95)) {
                return true;
            }
        }
        return false;
    }

    /** Puts the patch out (smoke): its entity goes, so the client's flames die down with steam. */
    static void extinguish(ServerLevel level, Entity thrown) {
        Areas.Fire fire = Areas.fire(thrown);
        Vec3 at = fire != null ? fire.centre() : thrown.position();
        Areas.removeFire(thrown);
        thrown.discard();
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.2f, 0.8f);
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.4, at.z, 24, 1.4, 0.3, 1.4, 0.03);
    }

    private static void fizzle(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.9f, 1.1f);
        level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.2, at.z, 12, 0.3, 0.1, 0.3, 0.02);
    }
}
