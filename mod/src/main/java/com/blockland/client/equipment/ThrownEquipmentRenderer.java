package com.blockland.client.equipment;

import com.blockland.equipment.EquipmentData;
import com.blockland.equipment.ThrownEquipment;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * Draws a thrown piece of equipment with its item model — the same look it has in third person, so a skin (mirrored
 * into custom_model_data by the build) shows in flight too. A knife flies point first and turns end over end, then
 * stays as it struck; grenades tumble with their speed and lie on their side at rest. An active area (smoke, fire) keeps
 * the spent grenade on the ground; the effects module draws the rest.
 */
final class ThrownEquipmentRenderer extends EntityRenderer<ThrownEquipment, ThrownEquipmentRenderer.State> {
    private final ItemModelResolver itemModelResolver;

    static final class State extends EntityRenderState {
        final ItemStackRenderState item = new ItemStackRenderState();
        float yaw, pitch, tumble;
        boolean knife, flying, broken;
    }

    ThrownEquipmentRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
        this.shadowRadius = 0.12f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ThrownEquipment entity, State state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        itemModelResolver.updateForNonLiving(state.item, entity.stack(), ItemDisplayContext.FIXED, entity);
        EquipmentData.Equipment item = entity.item();
        state.knife = item != null && EquipmentData.KNIFE.equals(item.kind);
        state.flying = entity.phase() == ThrownEquipment.Phase.FLYING;
        // a molotov is gone once it broke; its fire is the effects module's
        state.broken = item != null && item.shatters() && entity.phase() == ThrownEquipment.Phase.ACTIVE;
        state.yaw = entity.getYRot(partialTick);
        state.pitch = entity.getXRot(partialTick);
        state.tumble = entity.tumble(partialTick);
    }

    @Override
    public void render(State state, PoseStack pose, MultiBufferSource buffers, int light) {
        if (state.broken || state.item.isEmpty()) return;
        pose.pushPose();
        pose.translate(0, 0.08, 0);
        // local +Z along the direction of travel (yaw 0 faces +Z, positive pitch looks down)
        pose.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        if (state.knife) {
            pose.mulPose(Axis.XP.rotationDegrees(state.pitch));
            // the item's diagonal (handle low left, tip high right) along the flight, turning end over end
            pose.mulPose(Axis.YP.rotationDegrees(-90));
            pose.mulPose(Axis.ZP.rotationDegrees(-state.tumble));
            pose.mulPose(Axis.ZP.rotationDegrees(-45));
            pose.translate(0.1, 0, 0);
            pose.scale(0.7f, 0.7f, 0.7f);
        } else if (state.flying) {
            pose.mulPose(Axis.XP.rotationDegrees(state.tumble));
            pose.mulPose(Axis.ZP.rotationDegrees(state.tumble * 0.37f));
            pose.scale(0.55f, 0.55f, 0.55f);
        } else {
            // at rest on its side
            pose.translate(0, -0.06, 0);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            pose.scale(0.55f, 0.55f, 0.55f);
        }
        state.item.render(pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(state, pose, buffers, light);
    }
}
