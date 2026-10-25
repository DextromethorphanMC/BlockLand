package com.blockland.client.melee;

import com.blockland.BlockLand;
import com.blockland.anim.Spring;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.render.GpuModel;
import com.blockland.client.spray.SprayDecals;
import com.blockland.client.viewmodel.SwimPose;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.melee.MeleeData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

/**
 * First-person knife: drives the shared {@link ViewmodelRig} (draw / light / heavy / inspect clips over the idle,
 * player arms, procedural motion), wears the knife's 工匠 build (skin, a charm at {@code mount_charm}) and adds the
 * impact response — a weapon bounce and camera punch when the blade connects, stronger on a heavy hit or a wall.
 * Pure presentation: the controller decides when to attack.
 */
final class KnifeViewmodel {
    /** Roughly the right hand: impact bounces rotate about it. */
    private static final Vector3f PIVOT = new Vector3f(0.13f, -0.15f, -0.27f);

    enum Action { DRAW, LIGHT, HEAVY, INSPECT }

    private final Random random = new Random();
    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();
    private boolean ready;
    /** The model the rig was loaded from (the knife's, or its special skin's). */
    @Nullable
    private ResourceLocation loaded;
    @Nullable
    private MeleeData.Knife knife;
    @Nullable
    private Build build;
    @Nullable
    private boolean[] hidden;
    private GpuModel.Style style = GpuModel.Style.NONE;

    /** The weapon's rebound when the blade connects (degrees about the hand, millimetres back). */
    private final Spring bouncePitch = new Spring(0).config(340, 19);
    private final Spring bounceYaw = new Spring(0).config(340, 19);
    private final Spring bounceBack = new Spring(0).config(420, 24);

    private final Matrix4f root = new Matrix4f();

    /** @return false when the knife has no usable model (vanilla rendering is kept then) */
    boolean equip(MeleeData.Knife knife) {
        this.knife = knife;
        build = null;
        hidden = null;
        style = GpuModel.Style.NONE;
        if (!load(BlockLand.id(knife.model))) return false;
        play(Action.DRAW, knife.anims.draw, knife.drawTime);
        return ready;
    }

    /** Loads the rig from a model (the knife's own, or a special skin's with the same node contract, asset spec §30). */
    private boolean load(ResourceLocation location) {
        loaded = location;
        ready = rig.load(location, knife.anims.idle, knife.anims.walk, knife.anims.sprint, knife.anims.tacSprint);
        if (ready) {
            rig.motion().setHandling(knife.handling);
            rig.motion().swim().setStyle(SwimPose.Style.KNIFE);
            rig.motion().prone().setStyle(com.blockland.client.viewmodel.ProneLayer.Style.KNIFE);
        }
        return ready;
    }

    boolean isReady() {
        return ready && rig.isReady();
    }

    /**
     * Wears the held knife's build (skin and charm; a special skin's own model reloads the rig, a missing one falls back
     * to the knife's); cheap when nothing changed.
     */
    void setBuild(@Nullable Build next) {
        if (knife == null || loaded == null) return;
        Build complete = CraftCatalog.complete(knife, next);
        if (Objects.equals(complete, build)) return;
        ResourceLocation model = WeaponLook.modelOf(knife, complete);
        if (!model.equals(loaded) && !load(model)) load(BlockLand.id(knife.model));
        if (!isReady()) return;
        build = complete;
        hidden = WeaponLook.hiddenNodes(rig.model().source, knife, complete, rig.node("viewmodel"));
        rig.setAttachments(WeaponLook.attachments(knife, complete));
        MeleeData.Knife sprayed = knife;
        rig.setSprays(() -> SprayDecals.of(sprayed, complete), null);
        style = WeaponLook.style(WeaponLook.skinOf(knife, complete));
    }

    void setEventSink(Consumer<String> sink) {
        rig.setEventSink(sink);
    }

    /**
     * Plays a clip stretched to {@code seconds} (> 0) so it matches the attack timing in knives.json. The pose blends in
     * inertially (spec §31.4): a light swing straight out of the last one's follow-through (combos flow), a heavy one a
     * little softer, an inspect slowly.
     */
    boolean play(Action kind, String clip, float seconds) {
        float blend = switch (kind) {
            case DRAW -> 0;
            case LIGHT -> 0.07f;
            case HEAVY -> 0.1f;
            case INSPECT -> 0.22f;
        };
        return rig.play(kind, clip, seconds, blend);
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
        // the blade stops: the weapon rebounds against the cut (degrees, mm), the view jolts along it (degrees)
        bouncePitch.punch((float) (-Math.sin(angle) * 2.4 * strength));
        bounceYaw.punch((float) (Math.cos(angle) * 2.4 * strength));
        bounceBack.punch(4.5f * strength);
        // the camera (spec §32): a sharp jolt along the cut that the body follows through, a brief narrowing of the view
        // (the hit stops the eye), the head nudged forward, a crack of rattle and, on heavy hits, some rumble
        float down = (float) (Math.sin(angle) * 0.55 + 0.25) * punch * strength;
        camera().kick(-down, (float) (-Math.cos(angle) * 0.8 * punch * strength),
                (random.nextFloat() - 0.5f) * 1.6f * punch * strength, 0.7f);
        camera().punchFov(-1.4f * strength);
        camera().nudge(0, 0, -4f * strength);
        camera().shake(0.35f * strength, strength > 1.2f ? 0.25f * strength : 0.08f);
    }

    void update(float dt, AbstractClientPlayer player, boolean sprinting) {
        if (isReady()) rig.update(dt, player, sprinting, 0);
    }

    float sprintAmount() {
        return rig.sprintAmount();
    }

    float cameraPitch() {
        return rig.cameraPitch();
    }

    float cameraYaw() {
        return rig.cameraYaw();
    }

    float cameraRoll() {
        return rig.cameraRoll();
    }

    /** The camera's dynamic feedback: FOV punch and head movement are applied by the controller. */
    CameraRig camera() {
        return rig.camera();
    }

    /** Motion offsets × (sway, bob, impact bounce about the hand), applied to the viewmodel root. */
    private void buildRoot() {
        root.identity();
        rig.motion().applyOffsets(root, 0);
        root.translate(PIVOT);
        rig.motion().applyRotations(root, 0, !rig.hasSprintClips());
        root.rotateX((float) Math.toRadians(bouncePitch.get()));
        root.rotateY((float) Math.toRadians(bounceYaw.get()));
        root.translate(-PIVOT.x, -PIVOT.y, -PIVOT.z);
        root.translate(0, 0, bounceBack.get() * 0.001f);
    }

    /** Queues the knife and the arms; {@code base} maps viewmodel space to view space. */
    void draw(Matrix4f base, AbstractClientPlayer player, int blockLight, int skyLight) {
        if (!isReady()) return;
        buildRoot();
        rig.pose(root);
        rig.drawMeshes(base, hidden, style, blockLight, skyLight);
        rig.drawArms(base, player, hidden, blockLight, skyLight);
    }

    void submit() {
        ViewmodelRenderer.submit(rig, "BlockLand knife");
    }

    String stats() {
        return rig.stats();
    }
}
