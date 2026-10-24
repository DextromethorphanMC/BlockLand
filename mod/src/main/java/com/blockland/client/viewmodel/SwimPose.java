package com.blockland.client.viewmodel;

import com.blockland.anim.Spring;
import com.blockland.client.gltf.Pose;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A weapon in the water (spec §31.4): the swimming pose's layer of the first-person motion, and the rules that keep a
 * swim going while the weapon is used.
 * <p>
 * Vanilla's swimming pose is a sprint in water: stopping the sprint ends the swim. Weapons stop a <em>run</em> when the
 * hands need them ({@link #stopRunning}) but leave a swim alone, and the run's carries do not apply to a swim
 * ({@link #running}). While swimming, the weapon rests in a carry — held low and close, canted in, bobbing with the
 * strokes, the free arm out of view pulling the water — and is brought up forward quickly whenever the hands work it
 * (a shot, a reload, a cut: the rig's one-shot clips) or it is aimed, then sinks back into the carry. Aimed in the
 * water the sights come up more slowly ({@link #adsTime}) and drift with the body.
 */
public final class SwimPose {
    /** How a weapon is carried while swimming. */
    public enum Style {
        /** Low and close, canted towards the chest, muzzle down. */
        GUN(0.022f, -0.075f, 0.065f, -26, 20, -24),
        /** Held forward, the blade a little down. */
        KNIFE(0.006f, -0.028f, -0.018f, -12, 4, -10);

        final float x, y, z, pitch, yaw, roll;

        Style(float x, float y, float z, float pitch, float yaw, float roll) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pitch = pitch;
            this.yaw = yaw;
            this.roll = roll;
        }
    }

    /** Aiming takes this much longer in the water. */
    private static final float ADS_SLOWER = 1.6f;
    /** Blocks swum per stroke cycle. */
    private static final float STROKE = 2.4f;
    /** The weapon stays raised this long after the hands last worked it (automatic fire comes clip after clip). */
    private static final float RAISE_HOLD = 0.45f;

    // ---- rules shared by the weapon modules ----------------------------------------------------------------

    /** Sprinting on land: the run's carry and the "no sprint while busy" rules apply (a swim is a sprint too). */
    public static boolean running(Player player) {
        return player.isSprinting() && !player.isSwimming();
    }

    /** The hands need the weapon: a run stops; a swim goes on (stopping its sprint would stand the player up). */
    public static void stopRunning(Player player) {
        if (running(player)) player.setSprinting(false);
    }

    /** The ADS time in seconds for this player: slower while swimming. */
    public static float adsTime(Player player, float seconds) {
        return player.isSwimming() ? seconds * ADS_SLOWER : seconds;
    }

    // ---- the layer ----------------------------------------------------------------------------------------

    private Style style = Style.GUN;
    /** 0..1 in the swimming pose; 0..1 the weapon brought up from the carry. */
    private final Spring swim = new Spring(0).config(36, 11);
    private final Spring raise = new Spring(0).config(520, 42);
    /** The water's drag on the weapon as the body surges with each stroke. */
    private final Spring surge = new Spring(0).config(60, 9);
    private float sinceBusy = RAISE_HOLD;
    private boolean busy;
    private double stroke, drift;
    private int lastStroke;

    public void setStyle(Style style) {
        this.style = style == null ? Style.GUN : style;
    }

    /** The hands are working the weapon this frame (a one-shot clip plays): it comes up out of the carry. */
    public void hands(boolean busy) {
        this.busy = busy;
    }

    void update(float dt, Player player, float aim) {
        boolean swimming = player.isSwimming();
        swim.setTarget(swimming ? 1 : 0);
        sinceBusy = busy || aim > 0.05f ? 0 : sinceBusy + dt;
        boolean up = sinceBusy < RAISE_HOLD;
        // up fast (a shot must not wait on the pose), back down into the carry at the water's pace
        if (up) raise.config(520, 42);
        else raise.config(70, 16);
        raise.setTarget(up ? 1 : 0);
        Vec3 v = player.getDeltaMovement();
        float speed = (float) v.length() * 20;
        // the strokes advance with the distance swum; treading water keeps a slow paddle
        stroke += dt * Math.max(0.9, speed / STROKE * Math.PI * 2);
        if (stroke > Math.PI * 64) stroke -= Math.PI * 64;
        int s = (int) Math.floor(stroke / (Math.PI * 2));
        if (s != lastStroke && swimming) surge.punch(Math.min(1.2f, speed / 5f));
        lastStroke = s;
        drift += dt;
    }

    /** 0..1 how far into the swimming pose the weapon is. */
    public float amount() {
        return Mth.clamp(swim.get(), 0, 1);
    }

    /** Calming of the look sway while aimed: in the water the weapon wanders more. */
    float aimCalm(float aim) {
        return aim * (1 - 0.45f * amount());
    }

    private float carry() {
        return amount() * (1 - Mth.clamp(raise.get(), 0, 1.1f));
    }

    /** Screen-space offsets (before aiming): the carry, the stroke's bob and surge, the swimming ready pose. */
    void applyOffsets(Matrix4f root, float aim) {
        float w = amount();
        if (w < 0.001f) return;
        float c = carry(), ready = w * Mth.clamp(raise.get(), 0, 1) * (1 - aim), hip = 1 - 0.85f * aim;
        float pull = (float) Math.sin(stroke), glide = (float) Math.sin(stroke * 2);
        float x = c * style.x + pull * 0.005f * w * hip;
        float y = c * style.y - ready * 0.012f + glide * 0.004f * w * hip + driftY() * w;
        float z = c * style.z + ready * 0.018f + surge.get() * 0.010f * w * hip;
        root.translate(x, y, z);
    }

    /** Rotations about the weapon pivot (after aiming): the carry's tilt, the stroke's roll, the aimed drift. */
    void applyRotations(Matrix4f root, float aim) {
        float w = amount();
        if (w < 0.001f) return;
        float c = carry(), ready = w * Mth.clamp(raise.get(), 0, 1) * (1 - aim), hip = 1 - 0.75f * aim;
        float pull = (float) Math.sin(stroke), glide = (float) Math.sin(stroke * 2);
        float roll = c * style.roll + ready * 4 + pull * 2.4f * w * hip;
        float yaw = c * style.yaw + (float) Math.sin(drift * 0.53 + 1.1) * 0.55f * w;
        float pitch = c * style.pitch - ready * 3 + glide * 1.2f * w * hip - surge.get() * 1.6f * w * hip
                + (float) Math.sin(drift * 0.71) * 0.6f * w;
        root.rotateZ((float) Math.toRadians(roll));
        root.rotateY((float) Math.toRadians(yaw));
        root.rotateX((float) Math.toRadians(pitch));
    }

    /** The body bobbing in the water: a slow rise and fall the weapon rides, aimed or not. */
    private float driftY() {
        return (float) (Math.sin(drift * 0.9) * 0.0022 + Math.sin(drift * 1.7 + 0.4) * 0.0011);
    }

    /**
     * The free arm in the carry: out of view, low and to the left, pulling the water with each stroke; back on the
     * weapon as soon as the hands work it. {@code arm} is the {@code arm_L} joint (a child of the viewmodel root).
     */
    void poseFreeArm(Pose pose, int arm) {
        float c = carry();
        if (arm < 0 || c < 0.001f) return;
        float pull = (float) Math.sin(stroke);
        pose.addTranslation(arm, (-0.10f - 0.03f * pull) * c, (-0.32f + 0.05f * Math.max(0, pull)) * c, (0.10f - 0.06f * pull) * c);
    }
}
