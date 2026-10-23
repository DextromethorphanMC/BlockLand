package com.blockland.common;

/**
 * How a weapon feels in the hands (guns.json / knives.json {@code handling}; Gson, field names = JSON keys). Only the
 * first-person presentation reads it; gameplay numbers live in the weapon's own data.
 */
public final class Handling {
    /** Felt mass, 1 = pistol: heavier weapons trail turns and moves further and settle more slowly. */
    public float weight = 1;
    /** Multipliers of the look sway, the walk bob and the movement inertia. */
    public float sway = 1;
    public float bob = 1;
    public float inertia = 1;
    /** Seconds to bring the weapon out of a sprint (before it can fire or aim). */
    public float sprintOut = 0.22f;
    /** Seconds of tactical sprint (weapon raised) at the start of a sprint; it recharges while not sprinting. */
    public float tacticalSprint = 2.5f;
    /** Degrees the weapon cants while it comes up to the sights and back down. */
    public float adsTilt = 6;
    /**
     * Recoil profile of the first-person weapon (asset spec §31.4), multipliers of the house kick: how far a shot shoves
     * it back, how far the muzzle rises, how much it twists (roll / yaw jitter), how fast it snaps back (stiffness) and
     * how hard the camera shakes; {@code recoilPivot} = metres the kick pivots behind the grip (a shouldered stock).
     */
    public float recoilBack = 1;
    public float recoilRise = 1;
    public float recoilTwist = 1;
    public float recoilSnap = 1;
    public float recoilShake = 1;
    public float recoilPivot = 0;
    /**
     * Camera feedback of a shot (asset spec §32), multipliers: the kick (pitch / yaw), the roll, the FOV punch and the
     * low-frequency rumble (the weight of a heavy round; the high-frequency rattle is {@code recoilShake}).
     */
    public float cameraKick = 1;
    public float cameraRoll = 1;
    public float cameraFov = 1;
    public float cameraRumble = 1;
}
