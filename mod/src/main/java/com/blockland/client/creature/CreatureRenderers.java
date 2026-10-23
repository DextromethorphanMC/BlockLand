package com.blockland.client.creature;

import com.blockland.BlockLand;
import com.blockland.entity.BLEntities;
import com.blockland.entity.SlimeLicker;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/** Renderers for BlockLand's entity types: the Slime Licker (own model and texture) and the slime shot (its item). */
public final class CreatureRenderers {
    public static final ModelLayerLocation SLIME_LICKER = new ModelLayerLocation(BlockLand.id("slime_licker"), "main");
    private static final ResourceLocation SLIME_LICKER_TEXTURE = BlockLand.id("textures/entity/slime_licker.png");

    private CreatureRenderers() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterLayerDefinitions.getBus(modBus).addListener(event ->
                event.registerLayerDefinition(SLIME_LICKER, SlimeLickerModel::createBodyLayer));
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event -> {
            event.registerEntityRenderer(BLEntities.SLIME_LICKER.get(), SlimeLickerRenderer::new);
            event.registerEntityRenderer(BLEntities.SLIME_SHOT.get(), ThrownItemRenderer::new);
        });
    }

    /** Drawn like a zombie (arms reaching out when aggressive), with the modified model. */
    static final class SlimeLickerRenderer extends HumanoidMobRenderer<SlimeLicker, ZombieRenderState, SlimeLickerModel> {
        SlimeLickerRenderer(EntityRendererProvider.Context context) {
            super(context, new SlimeLickerModel(context.bakeLayer(SLIME_LICKER)), 0.5F);
        }

        @Override
        public ZombieRenderState createRenderState() {
            return new ZombieRenderState();
        }

        @Override
        public void extractRenderState(SlimeLicker entity, ZombieRenderState state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            state.isAggressive = entity.isAggressive();
            state.isConverting = false;
        }

        @Override
        public ResourceLocation getTextureLocation(ZombieRenderState state) {
            return SLIME_LICKER_TEXTURE;
        }
    }
}
