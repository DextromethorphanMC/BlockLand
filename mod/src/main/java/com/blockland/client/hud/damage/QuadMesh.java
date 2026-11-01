package com.blockland.client.hud.damage;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Free-form coloured quads (triangles as quads with a repeated corner) gathered in GUI coordinates and drawn as one
 * GUI element through vanilla's plain coloured pipeline — what the projected arrows are made of. Every quad is put in
 * the winding the GUI draws, so a face tipped away from the virtual camera still shows.
 */
final class QuadMesh {
    private float[] xy = new float[512];
    private int[] colors = new int[256];
    private int count;
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

    void tri(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2) {
        quad(x0, y0, c0, x1, y1, c1, x2, y2, c2, x2, y2, c2);
    }

    void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        if ((c0 | c1 | c2 | c3) >>> 24 == 0) return;
        // the GUI's quads turn the way vanilla's rectangles do (negative area with y down): turn this one the same way
        float area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0) + (x2 - x0) * (y3 - y0) - (y2 - y0) * (x3 - x0);
        if (area > 0) {
            add(x0, y0, c0);
            add(x3, y3, c3);
            add(x2, y2, c2);
            add(x1, y1, c1);
        } else {
            add(x0, y0, c0);
            add(x1, y1, c1);
            add(x2, y2, c2);
            add(x3, y3, c3);
        }
    }

    private void add(float x, float y, int color) {
        if (count == colors.length) {
            xy = Arrays.copyOf(xy, xy.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
        }
        xy[count * 2] = x;
        xy[count * 2 + 1] = y;
        colors[count++] = color;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }

    int quads() {
        return count / 4;
    }

    /** Hands the quads to the GUI as one element (nothing when empty). */
    void submit(GuiGraphics g) {
        if (count == 0) return;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle scissor = g.getScissorStack().peek();
        int x = (int) Math.floor(minX), y = (int) Math.floor(minY);
        ScreenRectangle area = new ScreenRectangle(x, y, (int) Math.ceil(maxX) - x + 1, (int) Math.ceil(maxY) - y + 1).transformMaxBounds(pose);
        ScreenRectangle bounds = scissor != null ? scissor.intersection(area) : area;
        if (bounds == null) return;
        g.getRenderState().submitGuiElement(new State(RenderPipelines.GUI, TextureSetup.noTexture(), pose, Arrays.copyOf(xy, count * 2),
                Arrays.copyOf(colors, count), scissor, bounds));
    }

    private record State(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, float[] xy, int[] colors,
                         @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer, float z) {
            for (int i = 0; i < colors.length; i++) consumer.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1], z).setColor(colors[i]);
        }
    }
}
