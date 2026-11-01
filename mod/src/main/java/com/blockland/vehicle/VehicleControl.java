package com.blockland.vehicle;

import net.minecraft.util.Mth;

/**
 * One tick of the driver's input (vehicle spec §4): numbered so the driver's client can match the server's state to the
 * input it had predicted with.
 *
 * @param throttle -1 (reverse) … 1 (forward)
 * @param steer    -1 (left) … 1 (right)
 * @param brake    the brake / handbrake (jump key)
 * @param boost    a little more speed (sprint key)
 * @param lookYaw  where the driver looks (Minecraft degrees): aircraft fly towards it
 * @param lookPitch the view's pitch (Minecraft sense, + = down)
 */
public record VehicleControl(int seq, float throttle, float steer, boolean brake, boolean boost, float lookYaw, float lookPitch) {
    public static final VehicleControl NONE = new VehicleControl(0, 0, 0, false, false, 0, 0);

    /** This input clamped to its range (a packet may carry anything). */
    public VehicleControl clamped() {
        return new VehicleControl(seq, clamp(throttle), clamp(steer), brake, boost, Float.isFinite(lookYaw) ? Mth.wrapDegrees(lookYaw) : 0,
                Float.isFinite(lookPitch) ? Mth.clamp(lookPitch, -90, 90) : 0);
    }

    /** The same input numbered {@code seq}. */
    public VehicleControl withSeq(int seq) {
        return new VehicleControl(seq, throttle, steer, brake, boost, lookYaw, lookPitch);
    }

    private static float clamp(float v) {
        return Float.isFinite(v) ? Math.max(-1, Math.min(1, v)) : 0;
    }
}
