package com.blockland.games.bomb;

import com.blockland.bomb.BombContent;
import com.blockland.bomb.BombData;
import com.blockland.combat.Blasts;
import com.blockland.combat.HitRecords;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the bomb does to the bodies around it: full {@code damage} within {@code innerRadius}, falling off (quadratically)
 * to {@code minDamage} at {@code radius}, everyone it reaches thrown away from it. Nobody owns the blast (it hurts both
 * teams) and it never touches a block — there is no vanilla explosion; the fire and smoke are the client's.
 */
final class BombBlast {
    private BombBlast() {
    }

    static void explode(ServerLevel level, Entity bomb, BombData.Blast blast) {
        Vec3 centre = bomb.position().add(0, 0.3, 0);
        float radius = Math.max(1, blast.radius), inner = Math.min(radius, blast.innerRadius);
        DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(BombContent.BLAST_DAMAGE), bomb, null);
        AABB box = new AABB(centre, centre).inflate(radius);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
            AABB body = victim.getBoundingBox();
            Vec3 nearest = new Vec3(Mth.clamp(centre.x, body.minX, body.maxX), Mth.clamp(centre.y, body.minY, body.maxY),
                    Mth.clamp(centre.z, body.minZ, body.maxZ));
            double distance = nearest.distanceTo(centre);
            if (distance > radius) continue;
            float exposure = blast.throughWalls ? 1 : exposure(level, bomb, centre, victim);
            if (exposure <= 0) continue;
            float t = distance <= inner ? 0 : (float) ((distance - inner) / Math.max(0.01, radius - inner));
            float amount = (blast.minDamage + (blast.damage - blast.minDamage) * (1 - t) * (1 - t)) * exposure;
            // nobody owns the blast: an older hit must not turn this death into someone's kill
            HitRecords.clear(victim);
            victim.invulnerableTime = 0;
            victim.hurtServer(level, source, amount);
            Vec3 away = body.getCenter().subtract(centre);
            if (away.lengthSqr() < 1e-4) away = new Vec3(0, 1, 0);
            away = away.normalize();
            double push = blast.knockback * (1 - t) * exposure;
            victim.push(away.x * push, Math.max(away.y, 0.3) * push * 0.6 + 0.1 * push, away.z * push);
            if (victim instanceof ServerPlayer player) player.hurtMarked = true;
        }
        // and what is not a body (vehicle hulls) takes it its own way; nobody owns it
        Blasts.reach(level, centre, radius, inner, blast.damage, null, bomb);
    }

    /** Share (0, 1/3, 2/3, 1) of the eyes, middle and feet the blast has a clear line to. */
    private static float exposure(ServerLevel level, Entity bomb, Vec3 centre, LivingEntity victim) {
        AABB body = victim.getBoundingBox();
        Vec3[] points = {victim.getEyePosition(), body.getCenter(), new Vec3(body.getCenter().x, body.minY + 0.15, body.getCenter().z)};
        int visible = 0;
        for (Vec3 point : points) {
            HitResult hit = level.clip(new ClipContext(centre, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bomb));
            if (hit.getType() == HitResult.Type.MISS) visible++;
        }
        return visible / 3f;
    }
}
