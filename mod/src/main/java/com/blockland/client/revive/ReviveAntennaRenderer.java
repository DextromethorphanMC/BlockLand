package com.blockland.client.revive;

import com.blockland.BlockLand;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ShadowPass;
import com.blockland.revive.ReviveAntennaBlock;
import com.blockland.revive.ReviveAntennaBlockEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Draws a revive antenna as its 3D model (asset spec §51) from its lower half with the weapon pipeline, lit by the world
 * like the ammo crate: turned to its facing, with the lit or the dark beacon node by the beacon's flash ({@link Beacon}),
 * whose light it attaches. While the sun's shadow map captures entities its triangles go through vanilla render types
 * (the dark beacon then), so the antenna casts a shadow.
 */
final class ReviveAntennaRenderer implements BlockEntityRenderer<ReviveAntennaBlockEntity> {
    static final ResourceLocation MODEL = BlockLand.id("models/block/revive_antenna.glb");

    private static final DrawList draws = new DrawList().scene(true);
    private final Matrix4f local = new Matrix4f(), base = new Matrix4f(), scratch = new Matrix4f();
    /** The rest pose of the model it was made for (a resource reload brings a new model). */
    @Nullable
    private GpuModel posedFor;
    private Pose pose;
    private int lit = -1, dark = -1;

    ReviveAntennaRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ReviveAntennaBlockEntity antenna, float partialTick, PoseStack stack, MultiBufferSource buffers, int light,
                       int overlay, Vec3 camera) {
        GpuModel model = ModelCache.get(MODEL);
        if (model == null || model.isClosed()) return;
        if (model != posedFor) {
            posedFor = model;
            pose = new Pose(model.source);
            pose.reset();
            pose.computeWorld(new Matrix4f());
            lit = model.source.node("beacon");
            dark = model.source.node("beacon_off");
        }
        boolean vanilla = ShadowPass.active();
        if (!vanilla) Beacon.attach(antenna);
        int hidden = !vanilla && Beacon.level(antenna.getBlockPos()) >= 0.5f ? dark : lit;
        float yaw = 180 - antenna.getBlockState().getValue(ReviveAntennaBlock.FACING).toYRot();
        local.translation(0.5f, 0, 0.5f).rotateY((float) Math.toRadians(yaw));
        if (vanilla) base.set(local);
        else base.set(RenderSystem.getModelViewMatrix()).mul(stack.last().pose()).mul(local);
        if (vanilla) stack.pushPose();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        GltfModel source = model.source;
        for (int index : source.order) {
            if (source.nodes.get(index).mesh < 0 || index == hidden) continue;
            model.addNode(draws, index, scratch.set(base).mul(pose.world[index]), GpuModel.Style.NONE, "", block, sky, 1);
        }
        if (vanilla) {
            draws.flushVanilla(stack, buffers);
            stack.popPose();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        if (color == null) {
            draws.clear();
            return;
        }
        draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand revive antenna");
    }

    /** Off screen too: the lower block's section may be out of sight while the mast and the beacon above it are not. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
