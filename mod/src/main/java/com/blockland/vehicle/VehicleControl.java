package com.blockland.vehicle;

/**
 * One tick of the driver's input (vehicle spec §4): numbered so the driver's client can match the server's state to the
 * input it had predicted with.
 *
 * @param throttle -1 (reverse) … 1 (forward)
 * @param steer    -1 (left) … 1 (right)
 * @param brake    the brake / handbrake (jump key)
 * @param boost    a little more speed (sprint key)
 */
public record VehicleControl(int seq, float throttle, float steer, boolean brake, boolean boost) {
    public static final VehicleControl NONE = new VehicleControl(0, 0, 0, false, false);

    /** This input clamped to its range (a packet may carry anything). */
    public VehicleControl clamped() {
        return new VehicleControl(seq, clamp(throttle), clamp(steer), brake, boost);
    }

    private static float clamp(float v) {
        return Float.isFinite(v) ? Math.max(-1, Math.min(1, v)) : 0;
    }
}
