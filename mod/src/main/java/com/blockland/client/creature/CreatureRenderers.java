package com.blockland.client.creature;

import com.blockland.BlockLand;
import com.blockland.entity.BLEntities;
import com.blockland.client.render.RenderClock;
import com.blockland.entity.CreeperZombie;
import com.blockland.entity.GhastLord;
import com.blockland.entity.SlimeLicker;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.GhastModel;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.AbstractZombieRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.GhastRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Renderers for BlockLand's entity types: the Slime Licker (own model and texture), the Creeper Zombie (the zombie model,
 * its own skin, a creeper's swelling and flashing while its fuse burns), the Ghast Lord (the vanilla ghast at a player's
 * size), the slime shot (its item) and the skill fireball (drawn exactly like vanilla's ghast fireball).
 */
public final class CreatureRenderers {
    public static final ModelLayerLocation SLIME_LICKER = new ModelLayerLocation(BlockLand.id("slime_licker"), "main");
    private static final ResourceLocation SLIME_LICKER_TEXTURE = BlockLand.id("textures/entity/slime_licker.png");
    private static final ResourceLocation CREEPER_ZOMBIE_TEXTURE = BlockLand.id("textures/entity/creeper_zombie.png");
    private static final ResourceLocation GHAST_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/ghast/ghast.png");
    private static final ResourceLocation GHAST_SHOOTING_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/ghast/ghast_shooting.png");

    private CreatureRenderers() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterLayerDefinitions.getBus(modBus).addListener(event ->
                event.registerLayerDefinition(SLIME_LICKER, SlimeLickerModel::createBodyLayer));
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event -> {
            event.registerEntityRenderer(BLEntities.SLIME_LICKER.get(), SlimeLickerRenderer::new);
            event.registerEntityRenderer(BLEntities.CREEPER_ZOMBIE.get(), CreeperZombieRenderer::new);
            event.registerEntityRenderer(BLEntities.GHAST_LORD.get(), GhastLordRenderer::new);
            event.registerEntityRenderer(BLEntities.SLIME_SHOT.get(), ThrownItemRenderer::new);
            // as vanilla registers its ghast fireball
            event.registerEntityRenderer(BLEntities.SKILL_FIREBALL.get(), context -> new ThrownItemRenderer<>(context, 3.0F, true));
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

    /**
     * The vanilla ghast (model and textures) shrunk to a player's size: a body about 0.8 blocks across at head height,
     * bobbing a little, its tentacles hanging free of the ground, so it fills the player's hit box (shots aim at what
     * they see). Shows the shooting face while it charges or just fired.
     */
    static final class GhastLordRenderer extends MobRenderer<GhastLord, GhastRenderState, GhastModel> {
        /** Of the vanilla model (4.5 times its mesh): a 0.8-block body. */
        private static final float SCALE = 0.8F / 4.5F;
        /** Blocks the model is lifted so the body's top is at about player height (1.8). */
        private static final float LIFT = 1.08F;

        GhastLordRenderer(EntityRendererProvider.Context context) {
            super(context, new GhastModel(context.bakeLayer(ModelLayers.GHAST)), 0.4F);
        }

        @Override
        public GhastRenderState createRenderState() {
            return new GhastRenderState();
        }

        @Override
        public void extractRenderState(GhastLord entity, GhastRenderState state, float partialTick) {
            super.extractRenderState(entity, state, partialTick);
            state.isCharging = entity.mouthOpen(RenderClock.nanos());
        }

        @Override
        public ResourceLocation getTextureLocation(GhastRenderState state) {
            return state.isCharging ? GHAST_SHOOTING_TEXTURE : GHAST_TEXTURE;
        }

        /** Called with y pointing down (the model's space): lift, then shrink. */
        @Override
        protected void scale(GhastRenderState state, PoseStack pose) {
            float bob = state.deathTime > 0 ? 0 : Mth.sin(state.ageInTicks * 0.1F) * 0.04F;
            pose.translate(0.0F, -(LIFT + bob), 0.0F);
            pose.scale(SCALE, SCALE, SCALE);
        }
    }
}
