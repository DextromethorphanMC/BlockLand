package com.blockland.client.viewmodel;

import com.blockland.anim.Spring;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.gltf.Pose;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

/**
 * The weapon lying down and diving (spec §47): the prone and dive layer of the first-person motion, fed by the movement
 * actions through {@link ViewmodelMotion.BodyMotion}, so every gun and knife follows without clips of its own.
 * <ul>
 *   <li>going down the weapon drops with the body and jolts as the chest meets the ground; getting up it is lifted and
 *       settles;</li>
 *   <li>lying still it rests low and steady; aimed it still drifts a little (the elbows on the ground);</li>
 *   <li>crawling it is carried canted across the chest, muzzle down, rocking with each elbow's pull, the free arm
 *       reaching forward and dragging the body along at the lower left — and brought up at once whenever the hands work
 *       it (a shot, a reload, a cut) or it is aimed, then lowered again;</li>
 *   <li>in a dive the arms throw it forward and up; the landing slams it down.</li>
 * </ul>
 */
public final class ProneLayer {
    /** How a weapon is carried while crawling. */
    public enum Style {
        /** Across the chest, canted to the left, muzzle down. */
        GUN(0.020f, -0.060f, 0.050f, -16, 14, -26),
        /** Close in front, the blade a little down. */
        KNIFE(0.004f, -0.032f, 0.010f, -10, 4, -12);

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

    /** Blocks crawled per stroke cycle (both elbows). */
    private static final float STROKE = 0.9f;
    /** The weapon stays up this long after the hands last worked it. */
    private static final float RAISE_HOLD = 0.5f;

    private Style style = Style.GUN;
    /** 0..1 lying down; 0..1 in the dive's throw; 0..1 the crawl carry (crawling and the hands free). */
    private final Spring prone = new Spring(0).config(120, 19);
    private final Spring dive = new Spring(0).config(150, 17);
    private final Spring carry = new Spring(0).config(60, 14);
    /** The body hitting the ground or getting up: mm down and degrees of muzzle dip about the pivot. */
    private final Spring drop = new Spring(0).config(170, 11);
    private final Spring dropPitch = new Spring(0).config(150, 10);
    private float sinceBusy = RAISE_HOLD;
    private boolean busy;
    private double stroke, drift;
    private int lastStroke;
    private float lastProne, lastDive;

    public void setStyle(Style style) {
        this.style = style == null ? Style.GUN : style;
    }

    /** The hands are working the weapon this frame (a one-shot clip plays): it comes up out of the crawl carry. */
    public void hands(boolean busy) {
        this.busy = busy;
    }

    void update(float dt, Player player, float aim, ViewmodelMotion.BodyMotion body, CameraRig camera, float heft) {
        float lying = Mth.clamp(body.prone(), 0, 1), diving = Mth.clamp(body.dive(), 0, 1), crawl = Mth.clamp(body.crawl(), 0, 1);
        // edges: down onto the chest (from standing or out of a dive), up again, thrown into a dive
        if (lying > 0.05f && lastProne <= 0.05f) {
            boolean landing = lastDive > 0.3f;
            float hard = (landing ? 2.2f : 1f) / heft;
            drop.punch(-5f * hard);
            dropPitch.punch(-3.5f * hard);
            camera.kick(landing ? -1.4f : -0.6f, 0, (landing ? 0.8f : 0.3f), landing ? 0.55f : 0.4f);
            camera.nudge(0, landing ? -14 : -6, 0);
            camera.shake(landing ? 0.12f : 0.04f, landing ? 0.35f : 0.12f);
        } else if (lying <= 0.95f && lastProne > 0.95f && lying < lastProne) {
            drop.punch(3f / heft);
            dropPitch.punch(2f / heft);
            camera.kick(0.35f, 0, 0, 0.35f);
        }
        if (diving > 0.05f && lastDive <= 0.05f) {
            dropPitch.punch(4f / heft);
            camera.kick(0.4f, 0, -0.4f, 0.4f);
        }
        lastProne = lying;
        lastDive = diving;
        prone.setTarget(lying);
        dive.setTarget(diving);
        sinceBusy = busy || aim > 0.05f ? 0 : sinceBusy + dt;
        boolean up = sinceBusy < RAISE_HOLD;
        // up fast (a shot must not wait on the pose), down into the carry at the crawl's pace
        if (up) carry.config(480, 40);
        else carry.config(60, 14);
        carry.setTarget(!up && crawl > 0.15f ? lying : 0);
        float speed = (float) Math.hypot(player.getX() - player.xo, player.getZ() - player.zo) * 20;
        stroke += dt * speed / STROKE * Math.PI * 2;
        if (stroke > Math.PI * 64) stroke -= Math.PI * 64;
        int s = (int) Math.floor(stroke / Math.PI);
        if (s != lastStroke && crawl > 0.3f) {
            // an elbow planted: the body heaves forward over it
            camera.kick(-0.12f, 0, (s % 2 == 0 ? 0.25f : -0.25f), 0.5f);
            camera.nudge(0, -2.5f, 0);
        }
        lastStroke = s;
        drift += dt;
    }

    /** 0..1 how far down the weapon is. */
    public float amount() {
        return Mth.clamp(prone.get(), 0, 1);
    }

    private float carryAmount() {
        return Mth.clamp(carry.get(), 0, 1.1f);
    }

    /** Screen-space offsets (before aiming): the rest on the ground, the crawl carry and its strokes, the dive's throw. */
    void applyOffsets(Matrix4f root, float aim) {
        float w = amount(), d = Mth.clamp(dive.get(), 0, 1);
        if (w < 0.001f && d < 0.001f && Math.abs(drop.get()) < 1e-3f) return;
        float c = carryAmount(), hip = 1 - 0.85f * aim;
        float pull = (float) Math.sin(stroke), plant = (float) Math.abs(Math.sin(stroke));
        float x = c * style.x + pull * 0.008f * c;
        float y = -0.006f * w * hip + c * style.y - plant * 0.006f * c + drop.get() * 0.001f + 0.02f * d * hip;
        float z = 0.004f * w * hip + c * style.z + plant * 0.008f * c - 0.05f * d * hip;
        root.translate(x, y, z);
    }

    /** Rotations about the weapon pivot (after aiming): the carry's cant, the strokes' rock, the aimed drift, the dive. */
    void applyRotations(Matrix4f root, float aim, float sway) {
        float w = amount(), d = Mth.clamp(dive.get(), 0, 1);
        if (w < 0.001f && d < 0.001f && Math.abs(dropPitch.get()) < 1e-3f) return;
        float c = carryAmount(), hip = 1 - 0.75f * aim;
        float pull = (float) Math.sin(stroke);
        // aimed lying down: the weapon rests on the elbows, but still breathes and wanders a little
        float wander = sway * w * aim;
        float roll = c * style.roll + pull * 5f * c + 6f * d * hip;
        float yaw = c * style.yaw + (float) Math.sin(drift * 0.47 + 0.8) * wander;
        float pitch = c * style.pitch + dropPitch.get() + 7f * d * hip
                + (float) Math.sin(drift * 0.61) * wander * 0.8f + (float) Math.sin(drift * 1.9 + 0.3) * wander * 0.25f;
        root.rotateZ((float) Math.toRadians(roll));
        root.rotateY((float) Math.toRadians(yaw));
        root.rotateX((float) Math.toRadians(pitch));
    }

    /**
     * The free arm in the crawl carry: reaching forward at the lower left, the elbow planted and pulling the body
     * along; back on the weapon as soon as the hands work it. {@code arm} is the {@code arm_L} joint (a child of the
     * viewmodel root).
     */
    void poseFreeArm(Pose pose, int arm) {
        float c = Mth.clamp(carryAmount(), 0, 1);
        if (arm < 0 || c < 0.001f) return;
        float pull = (float) Math.sin(stroke);
        pose.addTranslation(arm, (-0.04f - 0.02f * pull) * c, (-0.13f + 0.03f * Math.max(0, pull)) * c, (-0.10f + 0.09f * pull) * c);
    }
}
