package com.blockland.client.battleroyale;

import com.blockland.BlockLand;
import com.blockland.battleroyale.BrPlane;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Draws the drop plane with {@link BrPlaneModel}, nose along the route, twice the model's pixel size. */
final class BrPlaneRenderer extends EntityRenderer<BrPlane, BrPlaneRenderer.State> {
    private static final ResourceLocation TEXTURE = BlockLand.id("textures/entity/br_plane.png");
    private static final float SCALE = 2.0f;

    private final BrPlaneModel model;

    static final class State extends EntityRenderState {
        float yaw;
    }

    BrPlaneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new BrPlaneModel(context.bakeLayer(BrPlaneModel.LAYER));
        this.shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(BrPlane plane, State state, float partialTick) {
        super.extractRenderState(plane, state, partialTick);
        state.yaw = plane.getYRot();
    }

    @Override
    protected boolean affectedByCulling(BrPlane plane) {
        return false;
    }

    @Override
    public void render(State state, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180 - state.yaw));
        pose.scale(-SCALE, -SCALE, SCALE);
        model.setupAnim(state);
        model.renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(state, pose, buffers, light);
    }
}
