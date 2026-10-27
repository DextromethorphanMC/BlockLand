package com.blockland.client.backpack;

import com.blockland.BlockLand;
import com.blockland.backpack.BackpackData;
import com.blockland.backpack.BackpackItem;
import com.blockland.backpack.Backpacks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.EntityRenderersEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The worn backpack on a player's back (UI spec §2): a vanilla-style cuboid model in the body part's frame (it leans and
 * turns with the torso), its depth the backpack's ({@code backpacks.json} {@code depth}), its texture
 * {@code textures/entity/backpack/<id>.png} (64 × 32, laid out as {@code tools/assets/gen_backpack_items.py} paints it).
 * Drawn through vanilla's entity render types like the player model itself, so the lighting pack lights it and the
 * shadow pass captures it.
 */
final class BackpackLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
    private final Map<String, ModelPart> models = new HashMap<>();

    BackpackLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent, EntityModelSet models) {
        super(parent);
        for (BackpackData.Pack pack : BackpackData.packs()) this.models.put(pack.id(), models.bakeLayer(layer(pack)));
    }

    static ModelLayerLocation layer(BackpackData.Pack pack) {
        return new ModelLayerLocation(BlockLand.id("backpack/" + pack.id()), "main");
    }

    static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        for (BackpackData.Pack pack : BackpackData.packs()) event.registerLayerDefinition(layer(pack), () -> create(pack.depth()));
    }

    /**
     * Pixels in the body part's frame (x −4..4, y 0..12 down from the neck, z −2 front … +2 back): the pack against the
     * back, a lid over it, a pocket on its outside and two shoulder straps over the chest.
     */
    private static LayerDefinition create(int depth) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("pack", CubeListBuilder.create().texOffs(0, 0).addBox(-4, 1, 2, 8, 9, depth), PartPose.ZERO);
        root.addOrReplaceChild("lid", CubeListBuilder.create().texOffs(0, 16).addBox(-4, 0, 2, 8, 3, depth, new CubeDeformation(0.3F)),
                PartPose.ZERO);
        root.addOrReplaceChild("pocket", CubeListBuilder.create().texOffs(32, 0).addBox(-3, 5, 2 + depth, 6, 4, 1), PartPose.ZERO);
        root.addOrReplaceChild("strap_left", CubeListBuilder.create().texOffs(48, 0).addBox(1.5F, 0, -2.4F, 1, 8, 1), PartPose.ZERO);
        root.addOrReplaceChild("strap_right", CubeListBuilder.create().texOffs(48, 0).mirror().addBox(-2.5F, 0, -2.4F, 1, 8, 1),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 32);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, PlayerRenderState state, float yRot, float xRot) {
        if (state.isInvisible) return;
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level == null ? null : mc.level.getEntity(state.id);
        if (!(entity instanceof LivingEntity living)) return;
        ItemStack worn = Backpacks.worn(living);
        if (!(worn.getItem() instanceof BackpackItem item)) return;
        ModelPart model = models.get(item.pack().id());
        if (model == null) return;
        ResourceLocation texture = BlockLand.id("textures/entity/backpack/" + item.pack().id() + ".png");
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        model.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(texture)), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
