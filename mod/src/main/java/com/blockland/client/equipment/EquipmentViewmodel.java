package com.blockland.client.equipment;

import com.blockland.BlockLand;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.render.GpuModel;
import com.blockland.client.spray.SprayDecals;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import com.blockland.equipment.EquipmentData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * First-person equipment: drives the shared {@link ViewmodelRig} (draw / pull / hold / throw / lob / use / inspect clips
 * over the idle, player arms, procedural motion), wears the item's 工匠 build (skin, a charm at {@code mount_charm})
 * and adds the throw's body follow-through on the camera. Pure presentation: the controller decides what plays when.
 */
final class EquipmentViewmodel {
    enum Action { DRAW, PULL, HOLD, THROW, USE, INSPECT }

    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();
    private boolean ready;
    @Nullable
    private ResourceLocation loaded;
    @Nullable
    private EquipmentData.Equipment item;
    @Nullable
    private Build build;
    @Nullable
    private boolean[] hidden;
    private GpuModel.Style style = GpuModel.Style.NONE;
    private final Matrix4f root = new Matrix4f();

    /** @return false when the item has no usable model yet (vanilla rendering is kept then) */
    boolean equip(EquipmentData.Equipment item) {
        this.item = item;
        build = null;
        hidden = null;
        style = GpuModel.Style.NONE;
        if (item.model.isEmpty() || !load(BlockLand.id(item.model))) return false;
        play(Action.DRAW, item.anims.draw, item.drawTime);
        return ready;
    }

    private boolean load(ResourceLocation location) {
        loaded = location;
        ready = rig.load(location, item.anims.idle, item.anims.walk, item.anims.sprint, item.anims.tacSprint);
        if (ready) rig.motion().setHandling(item.handling);
        return ready;
    }

    boolean isReady() {
        return ready && rig.isReady();
    }

    /** Wears the held item's build (skin and charm; a special skin's own model reloads the rig); cheap when unchanged. */
    void setBuild(@Nullable Build next) {
        if (item == null || loaded == null) return;
        Build complete = CraftCatalog.complete(item, next);
        if (Objects.equals(complete, build)) return;
        ResourceLocation model = WeaponLook.modelOf(item, complete);
        if (!model.equals(loaded) && !load(model)) load(BlockLand.id(item.model));
        if (!isReady()) return;
        build = complete;
        hidden = WeaponLook.hiddenNodes(rig.model().source, item, complete, rig.node("viewmodel"));
        rig.setAttachments(WeaponLook.attachments(item, complete));
        EquipmentData.Equipment sprayed = item;
        rig.setSprays(() -> SprayDecals.of(sprayed, complete), null);
        style = WeaponLook.style(WeaponLook.skinOf(item, complete));
    }

    void setEventSink(Consumer<String> sink) {
        rig.setEventSink(sink);
    }

    /**
     * Plays a clip ({@code seconds} > 0 stretches it to the gameplay timing). The pose blends in inertially (spec §31.4):
     * the draw and the hold loop cut in (the hold continues the pull's last pose), the throw almost at once (it must
     * feel instant when the button comes up), the pull and the injection a little softer, an inspect slowly.
     */
    boolean play(Action kind, String clip, float seconds) {
        float blend = switch (kind) {
            case DRAW, HOLD -> 0;
            case THROW -> 0.04f;
            case PULL, USE -> 0.09f;
            case INSPECT -> 0.22f;
        };
        return rig.play(kind, clip, seconds, blend);
    }

    boolean hasClip(String clip) {
        return rig.hasClip(clip);
    }

    float clipLength(String clip) {
        return rig.clipLength(clip);
    }

    void stopAction() {
        rig.stopAction();
    }

    @Nullable
    Action action() {
        return rig.action();
    }

    /**
     * The item leaves the hand: the body follows the arm through (spec §32) — the view dips and turns with the shoulder,
     * the head moves forward; a lob is a smaller, upward motion.
     */
    void released(boolean lob, float weight) {
        float k = Math.max(0.4f, Math.min(1.5f, weight / 0.5f));
        if (lob) {
            camera().kick(0.5f * k, 0, -0.4f * k, 0.35f);
            camera().nudge(0, 2, -3);
        } else {
            camera().kick(-1.4f * k, -0.6f * k, -1.1f * k, 0.4f);
            camera().nudge(-2, 0, -9 * k);
            camera().punchFov(0.8f);
        }
    }

    /** The injector goes in: a sharp flinch the body absorbs. */
    void injected() {
        camera().kick(-0.9f, 0.3f, 0.8f, 0.7f);
        camera().shake(0.25f, 0.12f);
        camera().punchFov(-1.2f);
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

    /** Queues the item and the arms; {@code base} maps viewmodel space to view space. */
    void draw(Matrix4f base, AbstractClientPlayer player, int blockLight, int skyLight) {
        if (!isReady()) return;
        root.identity();
        rig.motion().applyOffsets(root, 0);
        rig.motion().applyRotations(root, 0, !rig.hasSprintClips());
        rig.pose(root);
        rig.drawMeshes(base, hidden, style, blockLight, skyLight);
        rig.drawArms(base, player, hidden, blockLight, skyLight);
    }

    void submit() {
        ViewmodelRenderer.submit(rig, "BlockLand equipment");
    }

    String stats() {
        return rig.stats();
    }
}
