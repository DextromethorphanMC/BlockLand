package com.blockland.client.profile;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

import javax.annotation.Nullable;

/**
 * The shader layer of an animated name card as one GUI element: a quad over the card with its own pipeline
 * ({@code blockland:core/name_card}). GUI elements carry no uniforms, so the vertex colour does: red and green the
 * animation clock (16 bits over 64 s), blue the effect and the card's frame count, alpha the opacity. UV0 runs 0..1
 * across the card.
 */
record CardEffectQuad(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, float x0, float y0, float x1, float y1,
                      int color, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
    @Override
    public void buildVertices(VertexConsumer consumer, float z) {
        consumer.addVertexWith2DPose(pose, x0, y0, z).setUv(0, 0).setColor(color);
        consumer.addVertexWith2DPose(pose, x0, y1, z).setUv(0, 1).setColor(color);
        consumer.addVertexWith2DPose(pose, x1, y1, z).setUv(1, 1).setColor(color);
        consumer.addVertexWith2DPose(pose, x1, y0, z).setUv(1, 0).setColor(color);
    }
}
