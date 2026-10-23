package com.blockland.client.font;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

import javax.annotation.Nullable;

/**
 * The glyphs of one piece of text that live on one atlas page, as one GUI element (the GUI renderer batches it with
 * other text on the same page). Corners are in the text's own space and go through {@code pose} like any GUI element;
 * a quad may lean (italic), so all four corners are kept.
 *
 * @param corners x, y of the four corners per quad (top-left, bottom-left, bottom-right, top-right)
 * @param uvs     u0, v0, u1, v1 per quad
 * @param colors  ARGB per quad
 */
record TextQuads(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, float[] corners, float[] uvs, int[] colors,
                 int quads, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
    @Override
    public void buildVertices(VertexConsumer consumer, float z) {
        for (int q = 0; q < quads; q++) {
            int c = q * 8, t = q * 4, color = colors[q];
            float u0 = uvs[t], v0 = uvs[t + 1], u1 = uvs[t + 2], v1 = uvs[t + 3];
            consumer.addVertexWith2DPose(pose, corners[c], corners[c + 1], z).setUv(u0, v0).setColor(color);
            consumer.addVertexWith2DPose(pose, corners[c + 2], corners[c + 3], z).setUv(u0, v1).setColor(color);
            consumer.addVertexWith2DPose(pose, corners[c + 4], corners[c + 5], z).setUv(u1, v1).setColor(color);
            consumer.addVertexWith2DPose(pose, corners[c + 6], corners[c + 7], z).setUv(u1, v0).setColor(color);
        }
    }
}
