package com.blockland.client.creature;

import com.blockland.BlockLand;
import com.blockland.entity.BLEntities;
import com.blockland.entity.CreeperZombie;
import com.blockland.entity.SlimeLicker;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.AbstractZombieRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Renderers for BlockLand's entity types: the Slime Licker (own model and texture), the Creeper Zombie (the zombie model,
 * its own skin, a creeper's swelling and flashing while its fuse burns) and the slime shot (its item).
 */
public final class CreatureRenderers {
    public static final ModelLayerLocation SLIME_LICKER = new ModelLayerLocation(BlockLand.id("slime_licker"), "main");
    private static final ResourceLocation SLIME_LICKER_TEXTURE = BlockLand.id("textures/entity/slime_licker.png");
    private static final ResourceLocation CREEPER_ZOMBIE_TEXTURE = BlockLand.id("textures/entity/creeper_zombie.png");

    private CreatureRenderers() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterLayerDefinitions.getBus(modBus).addListener(event ->
                event.registerLayerDefinition(SLIME_LICKER, SlimeLickerModel::createBodyLayer));
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event -> {
            event.registerEntityRenderer(BLEntities.SLIME_LICKER.get(), SlimeLickerRenderer::new);
            event.registerEntityRenderer(BLEntities.CREEPER_ZOMBIE.get(), CreeperZombieRenderer::new);
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

    /** What the creeper zombie renderer needs besides a zombie's: how far its fuse has burnt. */
    static final class CreeperZombieRenderState extends ZombieRenderState {
        float swelling;
    }

    /** A zombie with the creeper zombie's skin; while its fuse burns it swells and flashes white like a creeper. */
    static final class CreeperZombieRenderer extends AbstractZombieRenderer<CreeperZombie, CreeperZombieRenderState, ZombieModel<CreeperZombieRenderState>> {
        CreeperZombieRenderer(EntityRendererProvider.Context context) {
            super(context, new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_BABY)),
                    new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_INNER_ARMOR)), new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_OUTER_ARMOR)),
                    new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_BABY_INNER_ARMOR)),
                    new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_BABY_OUTER_ARMOR)));
        }

        @Override
        public CreeperZombieRenderState createRenderState() {
            return new CreeperZombieRenderState();
        }

        @Override
        public void extractRenderState(CreeperZombie entity, CreeperZombieRenderState state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            state.isConverting = false;
            state.swelling = entity.swelling();
        }

        @Override
        public ResourceLocation getTextureLocation(CreeperZombieRenderState state) {
            return CREEPER_ZOMBIE_TEXTURE;
        }

        /** The creeper's swell: wider and a little taller towards the end of the fuse, a fast tremble over it. */
        @Override
        protected void scale(CreeperZombieRenderState state, PoseStack pose) {
            float f = state.swelling;
            float tremble = 1.0F + Mth.sin(f * 100.0F) * f * 0.01F;
            f = Mth.clamp(f, 0.0F, 1.0F);
            f *= f;
            f *= f;
            pose.scale((1.0F + f * 0.4F) * tremble, (1.0F + f * 0.1F) / tremble, (1.0F + f * 0.4F) * tremble);
        }

        /** Flashing white faster as the fuse burns down. */
        @Override
        protected float getWhiteOverlayProgress(CreeperZombieRenderState state) {
            float f = state.swelling;
            if (f <= 0) return 0;
            return (int) (f * 10.0F) % 2 == 0 ? 0.0F : Mth.clamp(f, 0.5F, 1.0F);
        }
    }
}
