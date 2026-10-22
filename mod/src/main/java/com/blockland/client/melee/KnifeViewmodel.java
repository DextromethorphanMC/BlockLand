package com.blockland.client.melee;

import com.blockland.BlockLand;
import com.blockland.anim.Spring;
import com.blockland.client.render.GpuModel;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.melee.MeleeData;
import net.minecraft.client.player.AbstractClientPlayer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;
import java.util.function.Consumer;

/**
 * First-person knife: drives the shared {@link ViewmodelRig} (draw / light / heavy / inspect clips over the idle,
 * player arms, procedural motion) and adds the impact response — a weapon bounce and camera punch when the blade
 * connects, stronger on a heavy hit or a wall. Pure presentation: the controller decides when to attack.
 */
final class KnifeViewmodel {
    /** Roughly the right hand: impact bounces rotate about it. */
    private static final Vector3f PIVOT = new Vector3f(0.13f, -0.15f, -0.27f);

    enum Action { DRAW, LIGHT, HEAVY, INSPECT }

    private final Random random = new Random();
    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();
    private boolean ready;

    private final Spring bouncePitch = new Spring(0).config(340, 19);
    private final Spring bounceYaw = new Spring(0).config(340, 19);
    private final Spring bounceBack = new Spring(0).config(420, 24);
    private final Spring punchPitch = new Spring(0).config(260, 17);
    private final Spring punchYaw = new Spring(0).config(260, 17);
    private final Spring punchRoll = new Spring(0).config(220, 15);

    private final Matrix4f root = new Matrix4f();

    /** @return false when the knife has no usable model (vanilla rendering is kept then) */
    boolean equip(MeleeData.Knife knife) {
        ready = rig.load(BlockLand.id(knife.model), knife.anims.idle, knife.anims.sprint);
        if (ready) play(Action.DRAW, knife.anims.draw, knife.drawTime);
        return ready;
    }

    boolean isReady() {
        return ready && rig.isReady();
    }

    void setEventSink(Consumer<String> sink) {
        rig.setEventSink(sink);
    }

    /** Plays a clip stretched to {@code seconds} (> 0) so it matches the attack timing in knives.json. */
    boolean play(Action kind, String clip, float seconds) {
        return rig.play(kind, clip, seconds, false);
    }

    void stopAction() {
        rig.stopAction();
    }

    /** The playing one-shot, or null when idle. */
    Action action() {
        return rig.action();
    }

    /**
     * The blade connected.
     *
     * @param strength 0 = a miss, 1 = a light hit; heavy hits and walls go above 1
     * @param punch    camera punch from the attack data, in degrees
     */
    void impact(float strength, float punch, float slashAngle) {
        if (strength <= 0) return;
        double angle = Math.toRadians(slashAngle);
        // the blade stops: the weapon rebounds against the cut, the view jolts along it
        bouncePitch.impulse((float) (-Math.sin(angle) * 40 * strength));
        bounceYaw.impulse((float) (Math.cos(angle) * 40 * strength));
        bounceBack.impulse(0.35f * strength);
        punchPitch.impulse((float) (Math.sin(angle) * 22 * punch * strength) + 10 * punch * strength);
        punchYaw.impulse((float) (-Math.cos(angle) * 18 * punch * strength));
        punchRoll.impulse((random.nextFloat() - 0.5f) * 30 * punch * strength);
    }

    void update(float dt, AbstractClientPlayer player, boolean sprinting) {
        if (isReady()) rig.update(dt, player, sprinting, 0);
    }

    float sprintAmount() {
        return rig.sprintAmount();
    }

    float cameraPitch() {
        return rig.cameraPitch() + punchPitch.get() * 0.05f;
    }

    float cameraYaw() {
        return rig.cameraYaw() + punchYaw.get() * 0.05f;
    }

    float cameraRoll() {
        return rig.cameraRoll() + punchRoll.get() * 0.05f;
    }

    /** Motion offsets × (sway, bob, impact bounce about the hand), applied to the viewmodel root. */
    private void buildRoot() {
        root.identity();
        rig.motion().applyOffsets(root, 0);
        root.translate(PIVOT);
        // the procedural sprint carry is a pistol pose; knives bring their own sprint clip
        rig.motion().applyRotations(root, 0, false);
        root.rotateX((float) Math.toRadians(bouncePitch.get() * 0.05f));
        root.rotateY((float) Math.toRadians(bounceYaw.get() * 0.05f));
        root.translate(-PIVOT.x, -PIVOT.y, -PIVOT.z);
        root.translate(0, 0, bounceBack.get() * 0.05f);
    }

    /** Queues the knife and the arms; {@code base} maps viewmodel space to view space. */
    void draw(Matrix4f base, AbstractClientPlayer player, int blockLight, int skyLight) {
        if (!isReady()) return;
        buildRoot();
        rig.pose(root);
        rig.drawMeshes(base, null, GpuModel.Style.NONE, blockLight, skyLight);
        rig.drawArms(base, player, null, blockLight, skyLight);
    }

    void submit() {
        ViewmodelRenderer.submit(rig, "BlockLand knife");
    }

    String stats() {
        return rig.stats();
    }
}
