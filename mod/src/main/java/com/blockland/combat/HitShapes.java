package com.blockland.combat;

import com.blockland.movement.SlideRules;
import com.blockland.movement.Slides;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * What a shot or a blade can hit on an entity, shared by the gun and knife traces on both sides (the server's hit
 * registration and the attacker's prediction agree). An upright entity is its bounding box, its head the band from a
 * little below the eyes to the top; a sliding player is a low body box along the slide and a head box behind it,
 * matching the third-person slide pose ({@link SlideRules#hitBody} / {@link SlideRules#hitHead}).
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

    /** The entity's hit boxes, each grown by {@code inflate}. */
    public static List<Box> of(Entity entity, double inflate) {
        float yaw = Slides.slideYaw(entity);
        if (!Float.isNaN(yaw)) return slideBoxes(entity.position(), yaw, Slides.scaleOf(entity), SlideRules.get(), inflate);
        return List.of(Box.of(entity.getBoundingBox().inflate(inflate), false));
    }

    /** The first point of the segment on the entity (null on a miss); upright entities get the head band. */
    @Nullable
    public static Hit clip(Entity entity, Vec3 from, Vec3 to, double inflate) {
        Hit hit = clip(of(entity, inflate), from, to);
        if (hit == null || Slides.isSliding(entity)) return hit;
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

    /** The axis-aligned box around every hit box of the entity (sweeps, decal points). */
    public static AABB bounds(Entity entity) {
        List<Box> boxes = of(entity, 0);
        AABB bounds = boxes.get(0).bounds();
        for (int i = 1; i < boxes.size(); i++) bounds = bounds.minmax(boxes.get(i).bounds());
        return bounds;
    }

    /**
     * A sliding player's body (legs and torso, along the slide) and head (behind the hips), from its position.
     *
     * @param yaw   slide direction (Minecraft yaw)
     * @param scale the player's size scale
     */
    public static List<Box> slideBoxes(Vec3 position, float yaw, float scale, SlideRules rules, double inflate) {
        double rad = Math.toRadians(yaw);
        Vec3 along = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
        SlideRules.HitBody b = rules.hitBody;
        SlideRules.HitHead h = rules.hitHead;
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
