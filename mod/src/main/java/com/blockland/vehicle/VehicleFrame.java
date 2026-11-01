package com.blockland.vehicle;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector4d;

/**
 * Model space ↔ world (vehicle spec §3): the model's front is −Z and its +X the right; Minecraft's yaw turns the front
 * to (−sin yaw, 0, cos yaw). Pitch (+ = nose up) and roll (+ = right side up) tilt the hull on the ground. The server
 * (seats, muzzles), the clients and the renderer all place things with these same functions.
 */
public final class VehicleFrame {
    private VehicleFrame() {
    }

    /** Model space → world directions for a hull at {@code yaw} (Minecraft degrees), {@code pitch}, {@code roll}. */
    public static Quaterniond hull(float yaw, float pitch, float roll) {
        return new Quaterniond().rotateY(Math.PI - Math.toRadians(yaw)).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll));
    }

    /** The world point of model point {@code local} on a hull at {@code origin} turned by {@code hull}. */
    public static Vec3 toWorld(Vec3 origin, Quaterniond hull, double x, double y, double z) {
        Vector3d v = hull.transform(new Vector3d(x, y, z));
        return new Vec3(origin.x + v.x, origin.y + v.y, origin.z + v.z);
    }

    public static Vec3 direction(Quaterniond hull, double x, double y, double z) {
        Vector3d v = hull.transform(new Vector3d(x, y, z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** Minecraft's forward for a yaw (degrees). */
    public static Vec3 forward(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    /** Minecraft's right for a yaw (degrees). */
    public static Vec3 right(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.cos(r), 0, -Math.sin(r));
    }

    /**
     * Weapon {@code index}'s chain in model space: its mount (the turret's yaw for a gun on the turret), its yaw pivot
     * turned by its yaw (Minecraft sense, + = right), its pitch pivot raised by its elevation. Maps the pitch pivot's
     * space (muzzle offset, front −Z) to model space.
     *
     * @param yaws    each weapon's yaw relative to its mount (degrees)
     * @param pitches each weapon's elevation (degrees, + = up)
     */
    public static Matrix4d weapon(VehicleData.Vehicle v, int index, float[] yaws, float[] pitches) {
        VehicleData.Weapon w = v.weapons.get(index);
        Matrix4d m = new Matrix4d();
        if (w.onTurret() && index != 0 && !v.weapons.isEmpty()) {
            VehicleData.Weapon turret = v.weapons.get(0);
            m.translate(turret.pivot[0], turret.pivot[1], turret.pivot[2]).rotateY(-Math.toRadians(yaws[0]));
        }
        return m.translate(w.pivot[0], w.pivot[1], w.pivot[2])
                .rotateY(-Math.toRadians(yaws[index]))
                .translate(w.pitchPivot[0], w.pitchPivot[1], w.pitchPivot[2])
                .rotateX(Math.toRadians(pitches[index]));
    }

    /** The muzzle of weapon {@code index} in the world, and its unit direction: {muzzle, direction}. */
    public static Vec3[] muzzle(VehicleData.Vehicle v, int index, Vec3 origin, Quaterniond hull, float[] yaws, float[] pitches) {
        return muzzle(v, index, origin, hull, yaws, pitches, false);
    }

    /** {@link #muzzle}, or its twin mirrored across the hull's middle ({@code mirrored}: the other pod of a pair). */
    public static Vec3[] muzzle(VehicleData.Vehicle v, int index, Vec3 origin, Quaterniond hull, float[] yaws, float[] pitches,
                                boolean mirrored) {
        VehicleData.Weapon w = v.weapons.get(index);
        Matrix4d m = weapon(v, index, yaws, pitches);
        Vector4d p = m.transform(new Vector4d(w.muzzleOffset[0], w.muzzleOffset[1], w.muzzleOffset[2], 1));
        Vector4d d = m.transform(new Vector4d(0, 0, -1, 0));
        if (mirrored) {
            p.x = -p.x;
            d.x = -d.x;
        }
        Vec3 at = toWorld(origin, hull, p.x, p.y, p.z);
        Vec3 dir = direction(hull, d.x, d.y, d.z).normalize();
        return new Vec3[]{at, dir};
    }
}
