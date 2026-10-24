package com.blockland.client.decal;

import com.blockland.client.render.EntityPoses;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a cut lands on a body. On a model drawn in the last frame or two ({@link EntityPoses}) it is the drawn cube the
 * attack's line meets — the decal is anchored to that cube and follows the animation. Otherwise (not drawn: out of
 * sight or range) humanoid-shaped entities (players, zombies, villagers …) use the player model's parts
 * in their rest pose, so a decal sits on the visible torso, head or limb instead of the larger hitbox; everything
 * else uses its bounding box. Part space: origin at the feet, +Y up, +Z forward, +X to the entity's left; it turns
 * with the body yaw, the head part also with the head yaw and pitch about the neck.
 */
final class BodySurface {
    enum Part { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG, BOX }

    /** One player-model pixel in blocks (the player renderer scales the 32 px model by 15/16). */
    private static final float PX = 0.9375f / 16;
    private static final float NECK = 24 * PX;
    /** Blocks an overlay shell may stand off the cube it covers (a player's hat: half a pixel, 0.03). */
    private static final float SHELL = 0.045f;
    /** Part boxes (min xyz, max xyz) in part space for a 0.6-wide humanoid, slightly inflated. */
    private static final float[][] BOXES = {
            box(-4, 24, -4, 4, 32, 4),   // head
            box(-4, 12, -2, 4, 24, 2),   // body
            box(-8, 12, -2, -4, 24, 2),  // right arm
            box(4, 12, -2, 8, 24, 2),    // left arm
            box(-4, 0, -2, 0, 12, 2),    // right leg
            box(0, 0, -2, 4, 12, 2),     // left leg
    };

    private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
        float e = 0.15f;
        return new float[]{(x0 - e) * PX, (y0 - e) * PX, (z0 - e) * PX, (x1 + e) * PX, (y1 + e) * PX, (z1 + e) * PX};
    }

    /**
     * A point on a drawn model cube: the cube's key ({@link EntityPoses.Box#path}, {@link EntityPoses.Box#cube}), the
     * point and its face's normal in the cube's space (model pixels), the cube's box, and cube → world (relative to the
     * attacker's eye) at the hit.
     */
    record ModelAnchor(String path, int cube, Vector3f point, Vector3f normal, float[] bounds, Matrix4f toWorld) {
    }

    /** A point on a part: its position and the box face it lies on, in part space. */
    record Anchor(Part part, Vector3f point, Vector3f normal, float[] bounds) {
    }

    private BodySurface() {
    }

    static boolean isHumanoid(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        float w = entity.getBbWidth(), h = entity.getBbHeight();
        return h > 0.7f && Math.abs(w / h - 0.33f) < 0.09f;
    }

    /**
     * Part space → world at {@code partialTick}, relative to {@code origin} (the camera or the attacker's eye, so the
     * floats stay small and precise far from the world origin).
     */
    static Matrix4f partToWorld(Entity entity, Part part, float partialTick, Vec3 origin, Matrix4f into) {
        float bodyYaw = entity instanceof LivingEntity living ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot) : entity.getYRot(partialTick);
        into.identity()
                .translate((float) (Mth.lerp(partialTick, entity.xo, entity.getX()) - origin.x),
                        (float) (Mth.lerp(partialTick, entity.yo, entity.getY()) - origin.y),
                        (float) (Mth.lerp(partialTick, entity.zo, entity.getZ()) - origin.z))
                .rotateY((float) Math.toRadians(-bodyYaw));
        if (part == Part.BOX) return into;
        float scale = entity.getBbWidth() / 0.6f;
        into.scale(scale);
        if (part == Part.HEAD && entity instanceof LivingEntity living) {
            float headYaw = Mth.rotLerp(partialTick, living.yHeadRotO, living.yHeadRot);
            float pitch = living.getXRot(partialTick);
            into.translate(0, NECK, 0)
                    .rotateY((float) Math.toRadians(-Mth.wrapDegrees(headYaw - bodyYaw)))
                    .rotateX((float) Math.toRadians(pitch))
                    .translate(0, -NECK, 0);
        }
        return into;
    }

    /** Box of a part in part space (the entity's own box for {@link Part#BOX}). */
    static float[] bounds(Entity entity, Part part) {
        if (part != Part.BOX) return BOXES[part.ordinal()];
        AABB bb = entity.getBoundingBox();
        float hw = (float) bb.getXsize() / 2, h = (float) bb.getYsize();
        return new float[]{-hw, 0, -hw, hw, h, hw};
    }

    /**
     * The first part the ray from {@code from} along {@code dir} meets; for a ray that only grazed the body (melee
     * hits are generous) the ray is re-aimed at the torso. Falls back to the bounding box for other shapes.
     */
    static Anchor anchor(Entity entity, Vec3 from, Vec3 dir) {
        if (isHumanoid(entity)) {
            Anchor hit = cast(entity, from, dir, Part.HEAD, Part.LEFT_LEG);
            if (hit != null) return hit;
            Matrix4f body = partToWorld(entity, Part.BODY, 1, from, new Matrix4f());
            Vector3f chest = body.transformPosition(new Vector3f(0, 18 * PX, 0));
            Vec3 toChest = new Vec3(chest.x, chest.y, chest.z).normalize();
            hit = cast(entity, from, toChest, Part.HEAD, Part.LEFT_LEG);
            if (hit != null) return hit;
        }
        Anchor box = cast(entity, from, dir, Part.BOX, Part.BOX);
        if (box != null) return box;
        // last resort: the box side facing the attacker, at the ray's height
        float[] b = bounds(entity, Part.BOX);
        Vector3f localFrom = partToWorld(entity, Part.BOX, 1, from, new Matrix4f()).invert().transformPosition(new Vector3f());
        Vector3f n = Math.abs(localFrom.x) > Math.abs(localFrom.z) ? new Vector3f(Math.signum(localFrom.x), 0, 0) : new Vector3f(0, 0, Math.signum(localFrom.z));
        Vector3f p = new Vector3f(n.x * b[3], Mth.clamp(localFrom.y, b[1], b[4]), n.z * b[5]);
        return new Anchor(Part.BOX, p, n, b);
    }

    /**
     * The drawn cube the ray from {@code from} along {@code dir} meets first, with the model where the entity stands
     * now; a ray that only grazed the body is re-aimed at the centre of the cube passing closest to it. Null when the
     * entity's model was not drawn lately.
     */
    @Nullable
    static ModelAnchor onModel(Entity entity, Vec3 from, Vec3 dir) {
        EntityPoses.Capture pose = EntityPoses.latest(entity);
        if (pose == null || pose.size() == 0) return null;
        Vec3 at = entity.position();
        ModelAnchor hit = castModel(pose, from, dir, at);
        if (hit != null) return hit;
        Matrix4f toWorld = new Matrix4f();
        Vector3f centre = new Vector3f();
        Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        Vec3 aim = null;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < pose.size(); i++) {
            EntityPoses.Box box = pose.box(i);
            float[] b = box.bounds;
            pose.toWorld(box, from, at, toWorld).transformPosition((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2, centre);
            float along = centre.dot(d);
            if (along <= 0) continue;
            float off = centre.lengthSquared() - along * along;
            if (off < best) {
                best = off;
                aim = new Vec3(centre.x, centre.y, centre.z).normalize();
            }
        }
        return aim == null ? null : castModel(pose, from, aim, at);
    }

    /**
     * The nearest cube the ray enters — but an overlay shell (a player's hat, jacket and sleeves, a villager's robe: a
     * slightly larger cube of the same part or a child of it) gives way to the cube it covers just behind it, so the
     * decal lies on the skin, not on the shell's mostly transparent surface.
     */
    @Nullable
    private static ModelAnchor castModel(EntityPoses.Capture pose, Vec3 from, Vec3 dir, Vec3 at) {
        List<ModelAnchor> hits = new ArrayList<>();
        List<Float> distances = new ArrayList<>();
        Matrix4f toWorld = new Matrix4f(), toLocal = new Matrix4f();
        Vector3f world = new Vector3f();
        int nearest = -1;
        for (int i = 0; i < pose.size(); i++) {
            EntityPoses.Box box = pose.box(i);
            pose.toWorld(box, from, at, toWorld);
            toWorld.invert(toLocal);
            Vector3f o = toLocal.transformPosition(new Vector3f());
            Vector3f d = toLocal.transformDirection(new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
            Vector3f normal = new Vector3f();
            float t = slab(o, d, box.bounds, normal);
            if (t < 0) continue;
            Vector3f local = d.mul(t).add(o);
            float distance = toWorld.transformPosition(local, world).length();
            hits.add(new ModelAnchor(box.path, box.cube, local, normal, box.bounds, new Matrix4f(toWorld)));
            distances.add(distance);
            if (nearest < 0 || distance < distances.get(nearest)) nearest = hits.size() - 1;
        }
        if (nearest < 0) return null;
        ModelAnchor best = hits.get(nearest);
        float bestDistance = distances.get(nearest), front = bestDistance;
        for (int i = 0; i < hits.size(); i++) {
            ModelAnchor inner = hits.get(i);
            float distance = distances.get(i);
            if (distance > bestDistance && distance <= front + SHELL && covers(hits.get(nearest).path(), inner.path())) {
                best = inner;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Whether a cube of part {@code outer} can be a shell over one of part {@code inner}: the same part or a child. */
    private static boolean covers(String outer, String inner) {
        return outer.equals(inner) || !inner.isEmpty() && outer.startsWith(inner + "/");
    }

    @Nullable
    private static Anchor cast(Entity entity, Vec3 from, Vec3 dir, Part first, Part last) {
        Anchor best = null;
        double bestDistance = Double.MAX_VALUE;
        Matrix4f toWorld = new Matrix4f();
        for (int i = first.ordinal(); i <= last.ordinal(); i++) {
            Part part = Part.values()[i];
            partToWorld(entity, part, 1, from, toWorld);
            Matrix4f toLocal = new Matrix4f(toWorld).invert();
            Vector3f o = toLocal.transformPosition(new Vector3f());
            Vector3f d = toLocal.transformDirection(new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
            float[] b = bounds(entity, part);
            Vector3f normal = new Vector3f();
            float t = slab(o, d, b, normal);
            if (t < 0) continue;
            Vector3f local = new Vector3f(d).mul(t).add(o);
            double distance = toWorld.transformPosition(new Vector3f(local)).lengthSquared();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Anchor(part, local, normal, b);
            }
        }
        return best;
    }

    /** Ray / box entry distance (-1 = miss or starting inside); writes the entry face's normal. */
    private static float slab(Vector3f o, Vector3f d, float[] b, Vector3f normal) {
        float tMin = -Float.MAX_VALUE, tMax = Float.MAX_VALUE;
        int axis = -1;
        float sign = 0;
        for (int a = 0; a < 3; a++) {
            float origin = o.get(a), direction = d.get(a), lo = b[a], hi = b[a + 3];
            if (Math.abs(direction) < 1e-7f) {
                if (origin < lo || origin > hi) return -1;
                continue;
            }
            float t0 = (lo - origin) / direction, t1 = (hi - origin) / direction;
            float enterSign = -1;
            if (t0 > t1) {
                float swap = t0;
                t0 = t1;
                t1 = swap;
                enterSign = 1;
            }
            if (t0 > tMin) {
                tMin = t0;
                axis = a;
                sign = enterSign;
            }
            tMax = Math.min(tMax, t1);
            if (tMin > tMax) return -1;
        }
        if (axis < 0 || tMin < 0) return -1;
        normal.zero().setComponent(axis, sign);
        return tMin;
    }
}
