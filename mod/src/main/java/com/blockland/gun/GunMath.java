package com.blockland.gun;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Shot geometry shared by client prediction and server hit registration. */
public final class GunMath {
    private GunMath() {
    }

    /** Look direction for Minecraft yaw/pitch in degrees. */
    public static Vec3 look(float yaw, float pitch) {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    /**
     * Direction of one projectile: uniformly inside a cone of {@code spreadDegrees} around the look direction.
     * The same seed gives the same direction on client and server.
     */
    public static Vec3 spreadDirection(float yaw, float pitch, float spreadDegrees, long seed, int pellet) {
        Vec3 dir = look(yaw, pitch);
        if (spreadDegrees <= 0) return dir;
        long s = mix(seed + pellet * 0x9E3779B97F4A7C15L);
        double u = (s >>> 11) * 0x1.0p-53;
        s = mix(s);
        double v = (s >>> 11) * 0x1.0p-53;
        double theta = Math.toRadians(spreadDegrees) * Math.sqrt(u);
        double phi = 2 * Math.PI * v;
        Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up).normalize();
        Vec3 ortho = right.cross(dir).normalize();
        Vec3 offset = right.scale(Math.cos(phi)).add(ortho.scale(Math.sin(phi)));
        return dir.scale(Math.cos(theta)).add(offset.scale(Math.sin(theta))).normalize();
    }

    /**
     * Whether a hit at height {@code hitY} is on the head: the band from a little below the eyes to the top of the
     * bounding box (scales with the entity, works for players, zombies, skeletons, villagers…).
     */
    public static boolean isHeadshot(Entity target, double hitY) {
        double eye = target.getEyeY();
        double headBottom = eye - Math.max(0.12, target.getBbHeight() * 0.12);
        return target.getBbHeight() > 0.9 && hitY >= headBottom;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
