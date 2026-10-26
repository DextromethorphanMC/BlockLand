package com.blockland.client.binoculars;

import com.blockland.BlockLand;
import com.blockland.binoculars.BinocularsContent;
import com.blockland.client.render.RenderClock;
import com.blockland.client.scope.Optics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;

/**
 * The binoculars on this client (asset spec §52): while the item is in use they come up to the eyes — the held model
 * moves in front of the face, the screen darkens — and from there {@link Optics} takes over (zoom on the wheel, slower
 * turning, sway, the view through two eyepieces with a mil scale, bearing and rangefinder). Other players see both hands
 * bring them up to the face (an arm pose, from the vanilla-synced use).
 */
public final class ClientBinoculars {
    /** Both arms up, the hands meeting in front of the eyes; null = the arm pose enum could not be extended. */
    @Nullable
    private static HumanoidModel.ArmPose pose;
    private static boolean poseTried;
    /** 0 lowered … 1 at the eyes (this client's player). */
    private static float raised;
    private static long lastFrame;

    private ClientBinoculars() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> BinocularsData.reset()));
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> raised = 0);
    }

    /** Every frame: eases the raise towards whether the binoculars are in use and feeds the optics. */
    private static void frame() {
        long now = RenderClock.nanos();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean holding = player != null && (BinocularsContent.is(player.getMainHandItem()) || BinocularsContent.is(player.getOffhandItem()));
        if (!holding) {
            raised = 0;
            Optics.raiseHandOptic(null, 0, dt);
            return;
        }
        BinocularsData data = BinocularsData.get();
        boolean using = player.isUsingItem() && BinocularsContent.is(player.getUseItem()) && mc.screen == null;
        raised = using ? Math.min(1, raised + dt / Math.max(0.01f, data.raiseSeconds))
                : Math.max(0, raised - dt / Math.max(0.01f, data.lowerSeconds));
        Optics.raiseHandOptic(data.optic, raised, dt);
    }

    /** The third-person arm pose for a held pair: both hands at the eyes while in use, else the ordinary hold. */
    public static HumanoidModel.ArmPose armPose(LivingEntity entity, InteractionHand hand) {
        if (!entity.isUsingItem() || entity.getUsedItemHand() != hand) return HumanoidModel.ArmPose.ITEM;
        HumanoidModel.ArmPose raisedPose = pose();
        return raisedPose == null ? HumanoidModel.ArmPose.CROSSBOW_HOLD : raisedPose;
    }

    @Nullable
    private static HumanoidModel.ArmPose pose() {
        if (poseTried) return pose;
        poseTried = true;
        try {
            pose = HumanoidModel.ArmPose.create("BLOCKLAND_BINOCULARS", true, (model, state, arm) -> {
                // both arms, whichever is asked: upper arms forward and up, turned in, so the hands meet before the face
                aim(model.rightArm, model.head, -1);
                aim(model.leftArm, model.head, 1);
            });
        } catch (Throwable e) {
            BlockLand.LOGGER.warn("[BlockLand] Binoculars arm pose unavailable, using the crossbow hold", e);
        }
        return pose;
    }

    private static void aim(ModelPart arm, ModelPart head, int inwards) {
        arm.xRot = head.xRot - 1.95f;
        arm.yRot = head.yRot + inwards * 0.62f;
        arm.zRot = 0;
    }

    /**
     * First person while raising and lowering: the pair moves from the hip-level hold to the middle of the screen and up
     * to the eyes (once there the optics hide the hands). False at rest: vanilla's own hold.
     */
    public static boolean handTransform(PoseStack pose, HumanoidArm arm, float equip) {
        if (raised <= 0.001f) return false;
        float e = raised * raised * (3 - 2 * raised);
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        pose.translate(side * 0.56f * (1 - 0.92f * e), -0.52f - equip * 0.6f + 0.44f * e, -0.72f + 0.36f * e);
        pose.mulPose(Axis.YP.rotationDegrees(side * 8f * e));
        return true;
    }
}
