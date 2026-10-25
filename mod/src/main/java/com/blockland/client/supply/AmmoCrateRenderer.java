package com.blockland.client.supply;

import com.blockland.BlockLand;
import com.blockland.client.gltf.ClipSampler;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.ShadowPass;
import com.blockland.supply.AmmoCrateBlock;
import com.blockland.supply.AmmoCrateBlockEntity;
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
 * Draws an ammo crate as its 3D model (asset spec §46) with the weapon pipeline, lit by the world like a weapon in third
 * person: the crate turned to its block's facing, the lid and latches posed from the block entity's open / close times
 * by the model's {@code open} and {@code close} clips. While the sun's shadow map captures entities its triangles go
 * through vanilla render types instead, so the crate casts a shadow.
 */
final class AmmoCrateRenderer implements BlockEntityRenderer<AmmoCrateBlockEntity> {
    static final ResourceLocation MODEL = BlockLand.id("models/block/ammo_crate.glb");

    private static final DrawList draws = new DrawList().scene(true);
    private final Matrix4f local = new Matrix4f(), base = new Matrix4f(), scratch = new Matrix4f();
    /** The pose and clips of the model they were made for (a resource reload brings a new model). */
    @Nullable
    private GpuModel posedFor;
    private Pose pose;
    @Nullable
    private ClipSampler open, close;

    AmmoCrateRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AmmoCrateBlockEntity crate, float partialTick, PoseStack stack, MultiBufferSource buffers, int light,
                       int overlay, Vec3 camera) {
        GpuModel model = ModelCache.get(MODEL);
        if (model == null || model.isClosed()) return;
        posed(model, crate, partialTick);
        float yaw = 180 - crate.getBlockState().getValue(AmmoCrateBlock.FACING).toYRot();
        local.translation(0.5f, 0, 0.5f).rotateY((float) Math.toRadians(yaw));
        boolean vanilla = ShadowPass.active();
        if (vanilla) base.set(local);
        else base.set(RenderSystem.getModelViewMatrix()).mul(stack.last().pose()).mul(local);
        if (vanilla) stack.pushPose();
        int block = LightTexture.block(light), sky = LightTexture.sky(light);
        GltfModel source = model.source;
        for (int index : source.order) {
            if (source.nodes.get(index).mesh < 0) continue;
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
        draws.flush(color, depth, mc.gameRenderer.lightTexture().getTextureView(), "BlockLand ammo crate");
    }

    /** The lid this frame: shut, opening ({@code open}, then held open at its end) or closing ({@code close}). */
    private void posed(GpuModel model, AmmoCrateBlockEntity crate, float partialTick) {
        if (model != posedFor) {
            posedFor = model;
            pose = new Pose(model.source);
            GltfModel.Animation o = model.source.animation("open"), c = model.source.animation("close");
            open = o == null ? null : new ClipSampler(o);
            close = c == null ? null : new ClipSampler(c);
        }
        pose.reset();
        float opened = crate.openTime(partialTick) / 20f, closing = crate.closeTime(partialTick) / 20f;
        if (opened >= 0 && closing < 0 && open != null) {
            open.sample(Math.min(opened, open.duration()), pose);
        } else if (opened >= 0 && close != null && closing < close.duration()) {
            close.sample(closing, pose);
        }
        pose.computeWorld(new Matrix4f());
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
