package com.blockland.client.armor;

import com.blockland.BlockLand;
import com.blockland.armor.ArmorRules;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.render.GpuModel;
import com.blockland.client.viewmodel.SwimPose;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.function.Consumer;

/**
 * First-person armour plate (asset spec §50): drives the shared {@link ViewmodelRig} with {@code models/armor/plate.glb}
 * — both hands on the plate, the draw (it comes up from the pouch), the insert (lifted, turned and pushed down into the
 * carrier at the chest, out of sight; the clip's camera dips with it), the idle and the locomotion loops — with the
 * player's arms and procedural motion. Pure presentation: the armour module decides when to insert.
 */
final class PlateViewmodel {
    static final ResourceLocation MODEL = BlockLand.id("models/armor/plate.glb");

    enum Action { DRAW, INSERT }

    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();
    private final Matrix4f root = new Matrix4f();
    private boolean ready;

    /** @return false when the model is missing (vanilla's hand stays) */
    boolean equip() {
        ready = rig.load(MODEL, "idle", "walk", "sprint", "sprint");
        if (ready) {
            rig.motion().setHandling(ArmorRules.get().handling);
            rig.motion().swim().setStyle(SwimPose.Style.KNIFE);
            rig.play(Action.DRAW, "draw", 0, 0);
        }
        return ready;
    }

    boolean isReady() {
        return ready && rig.isReady();
    }

    void draw() {
        if (isReady()) rig.play(Action.DRAW, "draw", 0, 0.05f);
    }

    /** The insert, stretched to {@code seconds}. */
    void insert(float seconds) {
        if (isReady()) rig.play(Action.INSERT, "insert", seconds, 0.08f);
    }

    void stopAction() {
        rig.stopAction();
    }

    Action action() {
        return rig.action();
    }

    void setEventSink(Consumer<String> sink) {
        rig.setEventSink(sink);
    }

    void update(float dt, AbstractClientPlayer player, boolean sprinting) {
        if (isReady()) rig.update(dt, player, sprinting, 0);
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

    CameraRig camera() {
        return rig.camera();
    }

    /**
     * Queues the plate and the arms; {@code base} maps viewmodel space to view space. {@code dip}: degrees the head bows
     * to watch the plate go in (the camera's extra pitch); the hands hold the plate at the chest, so it holds still
     * against that and rises in the view.
     */
    void draw(Matrix4f base, AbstractClientPlayer player, int blockLight, int skyLight, float dip) {
        if (!isReady()) return;
        root.identity();
        if (dip != 0) root.rotateLocalX((float) Math.toRadians(dip));
        rig.motion().applyOffsets(root, 0);
        rig.motion().applyRotations(root, 0, !rig.hasSprintClips());
        rig.pose(root);
        rig.drawMeshes(base, null, GpuModel.Style.NONE, blockLight, skyLight);
        rig.drawArms(base, player, null, blockLight, skyLight);
    }

    void submit() {
        ViewmodelRenderer.submit(rig, "BlockLand armour plate");
    }
}
