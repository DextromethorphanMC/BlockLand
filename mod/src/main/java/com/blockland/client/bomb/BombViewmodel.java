package com.blockland.client.bomb;

import com.blockland.BlockLand;
import com.blockland.bomb.BombData;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.render.GpuModel;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import net.minecraft.client.player.AbstractClientPlayer;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The bomb in first person: drives the shared {@link ViewmodelRig} (draw / plant / inspect clips over the idle, the
 * player's arms, procedural motion; asset spec §39). Pure presentation: {@link ClientBomb} decides what plays when.
 */
final class BombViewmodel {
    enum Action { DRAW, PLANT, INSPECT }

    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();
    private final Matrix4f root = new Matrix4f();
    private boolean ready;

    /** @return false when the model is missing (vanilla rendering is kept then) */
    boolean equip(BombData data) {
        ready = !data.model.isEmpty()
                && rig.load(BlockLand.id(data.model), data.anims.idle, data.anims.walk, data.anims.sprint, data.anims.tacSprint);
        if (!ready) return false;
        rig.motion().setHandling(data.handling);
        play(Action.DRAW, data.anims.draw, data.drawTime);
        return true;
    }

    boolean isReady() {
        return ready && rig.isReady();
    }

    void setEventSink(Consumer<String> sink) {
        rig.setEventSink(sink);
    }

    /** Plays a clip ({@code seconds} > 0 stretches it): the draw cuts in, the plant a little softer, an inspect slowly. */
    boolean play(Action kind, String clip, float seconds) {
        float blend = switch (kind) {
            case DRAW -> 0;
            case PLANT -> 0.1f;
            case INSPECT -> 0.22f;
        };
        return rig.play(kind, clip, seconds, blend);
    }

    void stopAction() {
        rig.stopAction(0.15f);
    }

    @Nullable
    Action action() {
        return rig.action();
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

    /** Queues the bomb and the arms; {@code base} maps viewmodel space to view space. */
    void draw(Matrix4f base, AbstractClientPlayer player, int blockLight, int skyLight) {
        if (!isReady()) return;
        root.identity();
        rig.motion().applyOffsets(root, 0);
        rig.motion().applyRotations(root, 0, !rig.hasSprintClips());
        rig.pose(root);
        rig.drawMeshes(base, null, GpuModel.Style.NONE, blockLight, skyLight);
        rig.drawArms(base, player, null, blockLight, skyLight);
    }

    void submit() {
        ViewmodelRenderer.submit(rig, "BlockLand bomb");
    }
}
