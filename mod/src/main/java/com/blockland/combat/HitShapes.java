package com.blockland.combat;

import com.blockland.movement.action.ActionState;
import com.blockland.movement.action.BodyActions;
import com.blockland.movement.action.BodyBoxes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * What a shot or a blade can hit on an entity, shared by the gun and knife traces on both sides (the server's hit
 * registration and the attacker's prediction agree). An upright entity is its bounding box, its head the band from a
 * little below the eyes to the top; a player in a special movement ({@link BodyActions}: a slide, prone, a dive) is a
 * low body box along the action's direction and a head box (behind the hips in a slide, ahead lying down), matching its
 * third-person pose (the action's {@code hitBody} / {@code hitHead} data).
 */
public final class HitShapes {
    private HitShapes() {
    }

    /**
     * A box turned by {@code yaw} about Y (Minecraft yaw: 0 looks along +Z), given by its centre and half extents
     * along its own axes: x across, y up, z along the yaw.
     *
     * @param head hits on this box are head hits
     */
    public record Box(Vec3 center, double halfX, double halfY, double halfZ, float yaw, boolean head) {
        public static Box of(AABB box, boolean head) {
            return new Box(box.getCenter(), box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2, 0, head);
        }

        public Box inflate(double amount) {
            return new Box(center, halfX + amount, halfY + amount, halfZ + amount, yaw, head);
        }

        /** Where the segment enters the box (its start when it starts inside), empty on a miss. */
        public Optional<Vec3> clip(Vec3 from, Vec3 to) {
            AABB local = new AABB(-halfX, -halfY, -halfZ, halfX, halfY, halfZ);
            Vec3 a = toLocal(from), b = toLocal(to);
            if (local.contains(a)) return Optional.of(from);
            return local.clip(a, b).map(this::toWorld);
        }

        public boolean contains(Vec3 point) {
            Vec3 p = toLocal(point);
            return Math.abs(p.x) <= halfX && Math.abs(p.y) <= halfY && Math.abs(p.z) <= halfZ;
        }

        /** The outward normal of the side of this box nearest to {@code point} (where a hit on its surface struck). */
        public Vec3 faceNormal(Vec3 point) {
            Vec3 p = toLocal(point);
            double ax = Math.abs(p.x) / Math.max(1e-6, halfX), ay = Math.abs(p.y) / Math.max(1e-6, halfY),
                    az = Math.abs(p.z) / Math.max(1e-6, halfZ);
            Vec3 local = ax >= ay && ax >= az ? new Vec3(Math.signum(p.x), 0, 0)
                    : ay >= az ? new Vec3(0, Math.signum(p.y), 0) : new Vec3(0, 0, Math.signum(p.z));
            double rad = Math.toRadians(yaw), sin = Math.sin(rad), cos = Math.cos(rad);
            return new Vec3(local.x * cos - local.z * sin, local.y, local.x * sin + local.z * cos);
        }

        /** Where a line entering this box at {@code entry} along {@code dir} (unit) leaves it again. */
        public Vec3 exit(Vec3 entry, Vec3 dir) {
            double span = 2 * (halfX + halfY + halfZ) + 0.1;
            return clip(entry.add(dir.scale(span)), entry).orElse(entry);
        }

        /** The axis-aligned box around this one. */
        public AABB bounds() {
            double rad = Math.toRadians(yaw), c = Math.abs(Math.cos(rad)), s = Math.abs(Math.sin(rad));
            double x = halfX * c + halfZ * s, z = halfX * s + halfZ * c;
            return new AABB(center.x - x, center.y - halfY, center.z - z, center.x + x, center.y + halfY, center.z + z);
        }

        private Vec3 toLocal(Vec3 point) {
            double rad = Math.toRadians(yaw), sin = Math.sin(rad), cos = Math.cos(rad);
            double dx = point.x - center.x, dz = point.z - center.z;
            // across = (cos, 0, sin), along = (-sin, 0, cos)
            return new Vec3(dx * cos + dz * sin, point.y - center.y, -dx * sin + dz * cos);
        }

        private Vec3 toWorld(Vec3 local) {
            double rad = Math.toRadians(yaw), sin = Math.sin(rad), cos = Math.cos(rad);
            return new Vec3(center.x + local.x * cos - local.z * sin, center.y + local.y, center.z + local.x * sin + local.z * cos);
        }
    }

    /** Where a segment first meets an entity, and whether that point is on its head. */
    public record Hit(Vec3 point, boolean head) {
    }

    /**
     * An entity whose hit boxes are its own, not its (square, axis-aligned) bounding box — a vehicle's yawed hull boxes
     * (vehicle spec §6). Hits on them are never head hits.
     */
    public interface Shaped {
        List<Box> hitBoxes(double inflate);
    }

    /** The entity's hit boxes, each grown by {@code inflate}. */
    public static List<Box> of(Entity entity, double inflate) {
        if (entity instanceof Shaped shaped) {
            List<Box> boxes = shaped.hitBoxes(inflate);
            if (!boxes.isEmpty()) return boxes;
        }
        ActionState state = BodyActions.state(entity);
        if (state != null) {
            return bodyBoxes(entity.position(), state.yaw(), BodyActions.scaleOf(entity), BodyActions.hitBody(state.action()),
                    BodyActions.hitHead(state.action()), inflate);
        }
        return List.of(Box.of(entity.getBoundingBox().inflate(inflate), false));
    }

    /** The first point of the segment on the entity (null on a miss); upright entities get the head band. */
    @Nullable
    public static Hit clip(Entity entity, Vec3 from, Vec3 to, double inflate) {
        Hit hit = clip(of(entity, inflate), from, to);
        if (hit == null || BodyActions.isActing(entity) || entity instanceof Shaped) return hit;
        return new Hit(hit.point(), inHeadBand(entity, hit.point().y));
    }

    /** The first point of the segment on any of the boxes, null on a miss. */
    @Nullable
    public static Hit clip(List<Box> boxes, Vec3 from, Vec3 to) {
        Hit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Box box : boxes) {
            Optional<Vec3> point = box.clip(from, to);
            if (point.isEmpty()) continue;
            double distance = from.distanceToSqr(point.get());
            // where boxes overlap (neck), the head wins a tie
            if (distance < bestDistance - 1e-9 || distance < bestDistance + 1e-9 && box.head()) {
                best = new Hit(point.get(), box.head());
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * The part of the entity a hit at {@code point} struck: the hit box it lies on (an acting player's body or head), or
     * for an upright entity its bounding box — only the head band of it for a head hit. The wound's geometry (the side the
     * round went in, where it comes out) is that part's.
     */
    public static Box part(Entity entity, Vec3 point, boolean head) {
        List<Box> boxes = of(entity, 0.02);
        if (boxes.size() > 1) {
            Box best = boxes.get(0);
            double nearest = Double.MAX_VALUE;
            for (Box box : boxes) {
                if (box.contains(point) && box.head() == head) return box;
                double d = box.center().distanceToSqr(point);
                if (d < nearest) {
                    nearest = d;
                    best = box;
                }
            }
            return best;
        }
        Box body = boxes.get(0);
        if (!head || BodyActions.isActing(entity) || entity instanceof Shaped) return body;
        AABB box = entity.getBoundingBox().inflate(0.02);
        double bottom = entity.getEyeY() - Math.max(0.12, entity.getBbHeight() * 0.12);
        return Box.of(new AABB(box.minX, Math.min(bottom, box.maxY - 0.1), box.minZ, box.maxX, box.maxY, box.maxZ), true);
    }

    /** The axis-aligned box around every hit box of the entity (sweeps, decal points). */
    public static AABB bounds(Entity entity) {
        List<Box> boxes = of(entity, 0);
        AABB bounds = boxes.get(0).bounds();
        for (int i = 1; i < boxes.size(); i++) bounds = bounds.minmax(boxes.get(i).bounds());
        return bounds;
    }

    /**
     * The body (legs and torso, along the action's direction) and head of a player in a slide, prone or a dive, from
     * its position.
     *
     * @param yaw   the action's direction (Minecraft yaw)
     * @param scale the player's size scale
     */
    public static List<Box> bodyBoxes(Vec3 position, float yaw, float scale, BodyBoxes.Body b, BodyBoxes.Head h, double inflate) {
        double rad = Math.toRadians(yaw);
        Vec3 along = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
        Vec3 bodyCenter = position.add(along.scale((b.forward - b.back) / 2 * scale)).add(0, b.height / 2 * scale, 0);
        Box body = new Box(bodyCenter, b.halfWidth * scale, b.height / 2 * scale, (b.forward + b.back) / 2 * scale, yaw, false);
        Vec3 headCenter = position.add(along.scale(-h.back * scale)).add(0, h.height * scale, 0);
        Box head = new Box(headCenter, h.half * scale, h.half * scale, h.half * scale, yaw, true);
        return List.of(body.inflate(inflate), head.inflate(inflate));
    }

    /**
     * Whether a hit at height {@code y} on an upright entity is on the head: the band from a little below the eyes to
     * the top of the bounding box (scales with the entity; players, zombies, skeletons, villagers…).
     */
    public static boolean inHeadBand(Entity target, double y) {
        double eye = target.getEyeY();
        double headBottom = eye - Math.max(0.12, target.getBbHeight() * 0.12);
        return target.getBbHeight() > 0.9 && y >= headBottom;
    }
}
