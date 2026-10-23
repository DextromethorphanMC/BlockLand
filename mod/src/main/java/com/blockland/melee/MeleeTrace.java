package com.blockland.melee;

import com.blockland.combat.HitShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Where a knife attack lands: the nearest entity inside a swept volume around the view ray (in front of any wall),
 * otherwise the block it strikes. Pure function of the level, shared by the server (damage) and the attacking
 * client (instant effects), so both agree.
 */
public final class MeleeTrace {
    /** Attacker inside this cone behind the target counts as a backstab. */
    private static final double BEHIND_COS = Math.cos(Math.toRadians(70));

    private MeleeTrace() {
    }

    public enum Type { MISS, BLOCK, ENTITY }

    /**
     * @param point on the entity's box / the block face (the blade's end point for a miss)
     * @param face  struck block face, or the entity box side facing the attacker
     */
    public record Hit(Type type, Vec3 point, @Nullable Entity entity, @Nullable BlockPos pos, @Nullable Direction face) {
    }

    public static Hit trace(Level level, Player attacker, Vec3 eye, Vec3 dir, MeleeData.Attack attack) {
        Vec3 to = eye.add(dir.scale(attack.range));
        BlockHitResult block = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3 end = hitBlock ? block.getLocation() : to;
        double wall = eye.distanceToSqr(end);

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        AABB sweep = new AABB(eye, to).inflate(attack.radius + 1);
        for (Entity candidate : level.getEntities(attacker, sweep,
                e -> e.isPickable() && e.isAlive() && !e.isSpectator() && !attacker.isAlliedTo(e) && e != attacker.getVehicle())) {
            // the entity's hit boxes (a sliding player is low), swept by the blade's radius
            HitShapes.Hit hit = HitShapes.clip(candidate, eye, to, attack.radius + candidate.getPickRadius());
            if (hit == null) continue;
            double distance = eye.distanceToSqr(hit.point());
            // the blade does not pass through walls
            if (distance < bestDistance && distance <= wall + 1e-6) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best != null) {
            Vec3 point = surfacePoint(best, eye, dir);
            return new Hit(Type.ENTITY, point, best, null, Direction.getApproximateNearest(-dir.x, -dir.y, -dir.z));
        }
        if (hitBlock) return new Hit(Type.BLOCK, block.getLocation(), null, block.getBlockPos(), block.getDirection());
        return new Hit(Type.MISS, to, null, null, null);
    }

    /**
     * Where the view ray meets the entity's hit boxes, or (when it only grazed the swept volume) the point of the box
     * around them nearest to it.
     */
    private static Vec3 surfacePoint(Entity entity, Vec3 eye, Vec3 dir) {
        HitShapes.Hit exact = HitShapes.clip(entity, eye, eye.add(dir.scale(8)), 0);
        if (exact != null) return exact.point();
        AABB box = HitShapes.bounds(entity);
        Vec3 centre = box.getCenter();
        double t = Math.max(0, centre.subtract(eye).dot(dir));
        Vec3 onRay = eye.add(dir.scale(t));
        Vec3 clamped = new Vec3(clamp(onRay.x, box.minX, box.maxX), clamp(onRay.y, box.minY, box.maxY), clamp(onRay.z, box.minZ, box.maxZ));
        // push the point out to the box side facing the attacker
        return box.clip(eye, clamped.add(clamped.subtract(eye).normalize().scale(0.5))).orElse(clamped);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** The attacker stands behind where the target looks. */
    public static boolean isBehind(Entity attacker, Entity target) {
        float yaw = target instanceof LivingEntity living ? living.getYHeadRot() : target.getYRot();
        double rad = Math.toRadians(yaw);
        Vec3 facing = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
        Vec3 toAttacker = new Vec3(attacker.getX() - target.getX(), 0, attacker.getZ() - target.getZ());
        if (toAttacker.lengthSqr() < 1e-6) return false;
        return facing.dot(toAttacker.normalize()) < -BEHIND_COS;
    }
}
