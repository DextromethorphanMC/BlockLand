package com.blockland.client.armor;

import com.blockland.BlockLand;
import com.blockland.armor.ArmorContent;
import com.blockland.armor.VestItem;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ShadowPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The plate carrier on a player's body (asset spec §50): {@code models/armor/vest.glb} drawn in the body part's frame
 * (the model's +Y up and −Z forward are the torso's; origin at the neck, one unit a block), so it bends and turns with
 * the torso in every pose; a plate's mesh ({@code plate_1} … {@code plate_3}) shows only while that many plates are in
 * it. Drawn with the weapon pipeline like the third-person weapons; while the sun's shadow map captures entities its
 * triangles go through vanilla render types so it casts a shadow.
 */
final class VestLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
    static final ResourceLocation MODEL = BlockLand.id("models/armor/vest.glb");

    private static final DrawList draws = new DrawList().scene(true);
    private static final Matrix4f into = new Matrix4f(), node = new Matrix4f();
    /** The model the rest matrices and plate nodes below were taken from (a resource reload brings a new one). */
    @Nullable
    private static GpuModel restOf;
    @Nullable
    private static Pose rest;
    private static int[] plateNodes = new int[0];

    VestLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, PlayerRenderState state, float yRot, float xRot) {
        ItemStack chest = state.chestEquipment;
        if (state.isInvisible || !(chest.getItem() instanceof VestItem)) return;
        GpuModel model = ModelCache.get(MODEL);
        if (model == null) return;
        prepare(model);
        int plates = ArmorContent.stateOf(chest).count();
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        pose.scale(-1, -1, 1);                       // the model's +Y up, −Z forward (the part's own frame is Y down)
        boolean vanilla = ShadowPass.active();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        GltfModel source = model.source;
        if (vanilla) into.identity();
        else into.set(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        for (int i = 0; i < source.nodes.size(); i++) {
            if (source.nodes.get(i).mesh < 0 || hiddenPlate(i, plates)) continue;
            node.set(into).mul(rest.world[i]);
            model.addNode(draws, i, node, GpuModel.Style.NONE, "", block, sky, 1);
        }
        if (vanilla) {
            draws.flushVanilla(pose, buffers);
        } else {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
            GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
            if (color == null) draws.clear();
            else draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand plate carrier");
        }
        pose.popPose();
    }

    /** Whether node {@code i} is the mesh of a plate beyond the {@code plates} the carrier holds. */
    private static boolean hiddenPlate(int i, int plates) {
        for (int p = 0; p < plateNodes.length; p++) {
            if (plateNodes[p] == i) return p >= plates;
        }
        return false;
    }

    private static void prepare(GpuModel model) {
        if (model == restOf && rest != null) return;
        restOf = model;
        rest = new Pose(model.source);
        rest.computeWorld(new Matrix4f());
        plateNodes = new int[3];
        for (int p = 0; p < plateNodes.length; p++) plateNodes[p] = model.source.node("plate_" + (p + 1));
    }

    /** Resource reload: the model may have changed. */
    static void clear() {
        restOf = null;
        rest = null;
    }
}
