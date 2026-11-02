package com.blockland.client.graphics.shadow;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Copies a rectangle of one depth texture onto the same rectangle of another, bit for bit (a nearest blit between two
 * depth textures of one format). Blaze3D's own {@code CommandEncoder.copyTextureToTexture} hands GL the rectangle's
 * width and height where {@code glBlitFramebuffer} wants its far corner, so it copies the wrong texels for any
 * rectangle away from the origin (vanilla only copies whole textures with it). The framebuffers are the ones Blaze3D
 * keeps for the colour attachment with each depth texture — those its render passes on them use — and the bindings
 * are put back through its own state cache.
 */
final class DepthBlit {
    private DepthBlit() {
    }

    /**
     * @param color the colour attachment the render passes on both depth textures use (same size as them)
     * @param from  the depth texture read
     * @param to    the depth texture written
     */
    static void copy(GpuTexture color, GpuTexture from, GpuTexture to, int x, int y, int width, int height) {
        GlDevice device = (GlDevice) RenderSystem.getDevice();
        GlTexture colour = (GlTexture) color;
        int source = colour.getFbo(device.directStateAccess(), from), target = colour.getFbo(device.directStateAccess(), to);
        int read = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER), draw = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
        // a blit is clipped by the draw framebuffer's scissor; nothing else of the pipeline state touches it
        GlStateManager._disableScissorTest();
        GlStateManager._glBlitFrameBuffer(x, y, x + width, y + height, x, y, x + width, y + height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }
}
