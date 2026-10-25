package com.blockland.client.gun;

import com.blockland.client.equipment.effect.ClientEquipmentEffects;
import com.blockland.entity.BLEntities;
import com.blockland.gun.GunGrenade;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Launched grenades on this client (the hand cannon's 40 mm rounds, {@link GunGrenade}): drawn with their ammunition
 * item's model nose first along the flight, and their blast drawn as the frag grenade's (the equipment effects' public
 * {@link ClientEquipmentEffects#explosion}: fire, smoke, light and camera shake).
 */
final class GrenadeRounds {
    private GrenadeRounds() {
    }

    static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerEntityRenderer(BLEntities.GUN_GRENADE.get(), RoundRenderer::new));
        GunGrenade.setClientHooks(grenade -> {
            if (grenade.level() instanceof ClientLevel level) ClientEquipmentEffects.explosion(level, grenade.position().add(0, 0.3, 0));
        });
    }

    private static final class RoundRenderer extends EntityRenderer<GunGrenade, RoundRenderer.State> {
        private final ItemModelResolver items;

        static final class State extends EntityRenderState {
            final ItemStackRenderState item = new ItemStackRenderState();
            float yaw, pitch;
        }

        RoundRenderer(EntityRendererProvider.Context context) {
            super(context);
            items = context.getItemModelResolver();
            shadowRadius = 0.06f;
        }

        @Override
        public State createRenderState() {
            return new State();
        }

        @Override
        public void extractRenderState(GunGrenade entity, State state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            items.updateForNonLiving(state.item, entity.getItem(), ItemDisplayContext.FIXED, entity);
            state.yaw = entity.getYRot(partialTick);
            state.pitch = entity.getXRot(partialTick);
        }

        @Override
        public void render(State state, PoseStack pose, MultiBufferSource buffers, int light) {
            if (state.item.isEmpty()) return;
            pose.pushPose();
            // the item model's nose points north (-Z): turned onto the direction of travel (yaw 0 faces +Z)
            pose.mulPose(Axis.YP.rotationDegrees(180 - state.yaw));
            pose.mulPose(Axis.XP.rotationDegrees(-state.pitch));
            pose.scale(0.5f, 0.5f, 0.5f);
            state.item.render(pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
            super.render(state, pose, buffers, light);
        }
    }
}
