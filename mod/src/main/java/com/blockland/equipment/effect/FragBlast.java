package com.blockland.equipment.effect;

import com.blockland.BlockLand;
import com.blockland.combat.Blasts;
import com.blockland.combat.HitRecords;
import com.blockland.common.Sounds;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Frag grenade: full {@code damage} within {@code innerRadius}, falling off (quadratically) to {@code minDamage} at
 * {@code radius}, scaled by how much of the body the blast can see — three rays (eyes, middle, feet) from the
 * blast, so cover protects and nothing goes through walls. Everyone it reaches is shoved away from it. The visuals and
 * the camera shake are the client's (entity event 60).
 */
final class FragBlast {
    private FragBlast() {
    }

    static void explode(ServerLevel level, Entity thrown, JsonObject params, @Nullable LivingEntity owner) {
        Vec3 centre = thrown.position().add(0, 0.3, 0);
        float radius = Params.f(params, "radius", 6), inner = Math.min(radius, Params.f(params, "innerRadius", 1.6f));
        float damage = Params.f(params, "damage", 24), minDamage = Params.f(params, "minDamage", 3);
        float knockback = Params.f(params, "knockback", 0.9f);
        boolean throughWalls = Params.b(params, "throughWalls", false);
        // what the kill feed credits: the frag grenade, or what set it off (a grenade launcher's round)
        String weapon = params.has("weapon") ? params.get("weapon").getAsString() : EffectDamage.FRAG_ITEM;
        level.playSound(null, centre.x, centre.y, centre.z, Sounds.byId(BlockLand.id("equipment.frag_explode")), SoundSource.PLAYERS,
                5f, 0.92f + level.random.nextFloat() * 0.12f);
        AABB box = new AABB(centre, centre).inflate(radius);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
            AABB body = victim.getBoundingBox();
            Vec3 nearest = new Vec3(Mth.clamp(centre.x, body.minX, body.maxX), Mth.clamp(centre.y, body.minY, body.maxY),
                    Mth.clamp(centre.z, body.minZ, body.maxZ));
            double distance = nearest.distanceTo(centre);
            if (distance > radius) continue;
            float exposure = throughWalls ? 1 : exposure(level, thrown, centre, victim);
            if (exposure <= 0) continue;
            float t = distance <= inner ? 0 : (float) ((distance - inner) / Math.max(0.01, radius - inner));
            float amount = (minDamage + (damage - minDamage) * (1 - t) * (1 - t)) * exposure;
            HitRecords.record(victim, owner, weapon, false);
            victim.invulnerableTime = 0;
            victim.hurtServer(level, EffectDamage.source(level, EffectDamage.FRAG, thrown, owner), amount);
            // the shove: away from the blast, a little upward
            Vec3 away = body.getCenter().subtract(centre);
            if (away.lengthSqr() < 1e-4) away = new Vec3(0, 1, 0);
            away = away.normalize();
            double push = knockback * (1 - t) * exposure;
            victim.push(away.x * push, Math.max(away.y, 0.25) * push * 0.6 + 0.08 * push, away.z * push);
            if (victim instanceof ServerPlayer player) player.hurtMarked = true;
        }
        // and what is not a body (vehicle hulls) takes it its own way; "hullDamage" if the blast hits those harder
        Blasts.reach(level, centre, radius, inner, Params.f(params, "hullDamage", damage), owner, thrown);
    }

    /** Share (0, 1/3, 2/3, 1) of the eyes, middle and feet the blast has a clear line to. */
    private static float exposure(ServerLevel level, Entity thrown, Vec3 centre, LivingEntity victim) {
        AABB body = victim.getBoundingBox();
        Vec3[] points = {victim.getEyePosition(), body.getCenter(), new Vec3(body.getCenter().x, body.minY + 0.15, body.getCenter().z)};
        int visible = 0;
        for (Vec3 point : points) {
            HitResult hit = level.clip(new ClipContext(centre, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrown));
            if (hit.getType() == HitResult.Type.MISS) visible++;
        }
        return visible / 3f;
    }
}
