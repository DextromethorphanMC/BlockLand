package com.blockland.server.vehicle;

import com.blockland.BlockLand;
import com.blockland.combat.HitRecords;
import com.blockland.combat.HitShapes;
import com.blockland.combat.HurtCheck;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.VehiclePackets;
import com.blockland.vehicle.VehicleContent;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import com.blockland.vehicle.VehicleShell;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * What a vehicle's weapon does when it fires (vehicle spec §6), server side: a cannon launches a {@link VehicleShell}
 * from its muzzle; a machine gun traces its round at once — blocks, then the hit boxes of whatever it crosses
 * ({@link HitShapes}: bodies, other vehicles' hulls), never its own vehicle, its riders or the gunner's allies — hurts
 * what it struck as a bullet (credited to the gunner and the vehicle) and tells everyone tracking the vehicle where it
 * went and whether a body really lost health ({@link HurtCheck}: only then it bleeds).
 */
final class VehicleWeapons {
    private static final ResourceKey<DamageType> BULLET = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("bullet"));

    private VehicleWeapons() {
    }

    static void fire(ServerLevel level, VehicleEntity vehicle, int index, @Nullable LivingEntity gunner) {
        VehicleData.Weapon w = vehicle.data.weapons.get(index);
        Vec3[] muzzle = vehicle.muzzle(index);
        level.broadcastEntityEvent(vehicle, (byte) (VehicleEntity.EVENT_FIRE + index));
        vehicle.playSound("fire_" + w.id, w.shell() ? 8f : 3f);
        if (w.shell()) {
            VehicleShell shell = VehicleContent.SHELL.get().create(level, EntitySpawnReason.TRIGGERED);
            if (shell == null) return;
            shell.launch(vehicle, w, gunner, muzzle[0], muzzle[1]);
            level.addFreshEntity(shell);
            return;
        }
        hitscan(level, vehicle, index, w, gunner, muzzle[0], spread(level, muzzle[1], w.spread));
    }

    /** {@code dir} turned at random within a cone of half-angle {@code degrees}. */
    private static Vec3 spread(ServerLevel level, Vec3 dir, float degrees) {
        if (degrees <= 0) return dir;
        double angle = Math.toRadians(degrees) * Math.sqrt(level.random.nextDouble());
        double around = level.random.nextDouble() * Math.PI * 2;
        Vec3 side = Math.abs(dir.y) < 0.95 ? dir.cross(new Vec3(0, 1, 0)).normalize() : new Vec3(1, 0, 0);
        Vec3 up = side.cross(dir).normalize();
        Vec3 offset = side.scale(Math.cos(around)).add(up.scale(Math.sin(around))).scale(Math.tan(angle));
        return dir.add(offset).normalize();
    }

    private static void hitscan(ServerLevel level, VehicleEntity vehicle, int index, VehicleData.Weapon w, @Nullable LivingEntity gunner,
                                Vec3 from, Vec3 dir) {
        Vec3 to = from.add(dir.scale(w.range));
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3 end = hitBlock ? block.getLocation() : to;
        Entity best = null;
        HitShapes.Hit bestHit = null;
        double bestDistance = from.distanceToSqr(end);
        for (Entity candidate : level.getEntities(vehicle, new AABB(from, end).inflate(2.0),
                e -> e.isPickable() && e.isAlive() && !e.isSpectator() && !vehicle.hasIndirectPassenger(e)
                        && (gunner == null || !gunner.isAlliedTo(e)))) {
            HitShapes.Hit hit = HitShapes.clip(candidate, from, end, Math.max(0.05, candidate.getPickRadius()));
            if (hit == null) continue;
            double distance = from.distanceToSqr(hit.point());
            if (distance < bestDistance) {
                best = candidate;
                bestHit = hit;
                bestDistance = distance;
            }
        }
        boolean wounded = false;
        int face = hitBlock ? block.getDirection().ordinal() : -1;
        if (best != null) {
            end = bestHit.point();
            face = -1;
            float amount = w.damage * (bestHit.head() ? 1.8f : 1);
            if (best instanceof LivingEntity living) {
                HitRecords.record(living, gunner, BlockLand.id(vehicle.data.id).toString(), bestHit.head());
                living.invulnerableTime = 0;
            }
            DamageSource source = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BULLET), vehicle, gunner);
            boolean wasSpectator = best.isSpectator();
            float before = HurtCheck.vitality(best);
            if (best.hurtServer(level, source, amount)) {
                boolean killed = !best.isAlive() || best instanceof LivingEntity l && l.isDeadOrDying() || !wasSpectator && best.isSpectator();
                wounded = HurtCheck.hurt(best, before, killed);
            }
        }
        BLNetwork.sendToTracking(vehicle, new VehiclePackets.S2CShot(vehicle.getId(), index, end.x, end.y, end.z, face,
                best == null ? -1 : best.getId(), wounded));
    }
}
