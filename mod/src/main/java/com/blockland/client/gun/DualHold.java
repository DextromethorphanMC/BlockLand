package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderPlayerEvent;

import javax.annotation.Nullable;

/**
 * Third person of dual guns (asset spec §26): one item is a pair, so the held model is drawn in both hands and each arm
 * points its gun along the look, like a handgun held out at each shoulder. Only the render state changes: the off
 * hand (when it holds nothing) gets the same item model and the same arm pose.
 */
public final class DualHold {
    /** Arm straight out along the look, turned a touch inwards so both guns meet at the crosshair; null = unsupported. */
    @Nullable
    private static final HumanoidModel.ArmPose POSE = createPose();

    private DualHold() {
    }

    static void init() {
        RenderPlayerEvent.Pre.BUS.addListener(DualHold::pre);
    }

    @Nullable
    private static HumanoidModel.ArmPose createPose() {
        try {
            return HumanoidModel.ArmPose.create("BLOCKLAND_DUAL_GUN", false, (model, state, arm) -> {
                ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
                part.xRot = (float) (-Math.PI / 2) + model.head.xRot;
                part.yRot = model.head.yRot + (arm == HumanoidArm.RIGHT ? -0.08f : 0.08f);
                part.zRot = 0;
            });
        } catch (Throwable e) {
            // the enum could not be extended in this environment: dual guns keep the two-handed hold
            BlockLand.LOGGER.warn("[BlockLand] Dual gun arm pose unavailable, using the crossbow hold", e);
            return null;
        }
    }

    /** The main hand's arm pose for a gun stack (called by the gun item's client extension). */
    public static HumanoidModel.ArmPose pose(ItemStack stack) {
        boolean dual = GunContent.gunOf(stack).map(GunData.Gun::isDual).orElse(false);
        return dual && POSE != null ? POSE : HumanoidModel.ArmPose.CROSSBOW_HOLD;
    }

    /** Before a player is drawn: the off hand gets the pair's second gun and the same pose. */
    private static boolean pre(RenderPlayerEvent.Pre event) {
        if (POSE == null) return false;
        PlayerRenderState state = event.getState();
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level == null ? null : mc.level.getEntity(state.id);
        if (!(entity instanceof Player player) || !player.getOffhandItem().isEmpty()) return false;
        ItemStack held = player.getMainHandItem();
        if (!GunContent.gunOf(held).map(GunData.Gun::isDual).orElse(false)) return false;
        HumanoidArm off = state.mainArm.getOpposite();
        ItemStackRenderState item = off == HumanoidArm.LEFT ? state.leftHandItem : state.rightHandItem;
        mc.getItemModelResolver().updateForLiving(item, held,
                off == HumanoidArm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, player);
        if (off == HumanoidArm.LEFT) state.leftArmPose = POSE;
        else state.rightArmPose = POSE;
        return false;
    }
}
