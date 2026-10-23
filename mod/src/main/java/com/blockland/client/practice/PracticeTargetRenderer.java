package com.blockland.client.practice;

import com.blockland.entity.BLEntities;
import com.blockland.entity.PracticeTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Draws 枪械瞄准练习's fake players as players: the player model, one of the default (wide-arm) skins picked by the
 * server, the crouch offset and scale of a real player, and the name tag ("靶子 #3").
 */
public final class PracticeTargetRenderer extends HumanoidMobRenderer<PracticeTarget, PlayerRenderState, PlayerModel> {
    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    private static final PlayerSkin[] TEXTURES = new PlayerSkin[SKINS.length];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            TEXTURES[i] = new PlayerSkin(ResourceLocation.withDefaultNamespace("textures/entity/player/wide/" + SKINS[i] + ".png"),
                    null, null, null, PlayerSkin.Model.WIDE, true);
        }
    }

    PracticeTargetRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        // the armoured variant of the drill (mode setting) wears a helmet and chestplate
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getEquipmentRenderer()));
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerEntityRenderer(BLEntities.PRACTICE_TARGET.get(), PracticeTargetRenderer::new));
    }

    @Override
    public PlayerRenderState createRenderState() {
        return new PlayerRenderState();
    }

    @Override
    public void extractRenderState(PracticeTarget entity, PlayerRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.skin = TEXTURES[Math.floorMod(entity.skin(), TEXTURES.length)];
        state.id = entity.getId();
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerRenderState state) {
        return state.skin.texture();
    }

    @Override
    public Vec3 getRenderOffset(PlayerRenderState state) {
        Vec3 offset = super.getRenderOffset(state);
        return state.isCrouching ? offset.add(0, state.scale * -2.0F / 16.0, 0) : offset;
    }

    @Override
    protected void scale(PlayerRenderState state, PoseStack pose) {
        pose.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
