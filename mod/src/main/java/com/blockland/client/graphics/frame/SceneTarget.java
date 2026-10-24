package com.blockland.client.graphics.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import javax.annotation.Nullable;

/**
 * The internal-resolution target the world, the hand and the viewmodel are drawn into while it stands in for Minecraft's
 * main target. One quirk: after the hand phase vanilla clears the main depth before drawing the HUD — the depth the post
 * chain needs to tell the hand and the viewmodel from the world. Every caller of {@link #getDepthTexture()} only clears
 * it, so from the hand phase's own clear on, the next clears get a stand-in (render passes use the views, which never
 * change). Fields are read directly where vanilla copies depth (Fabulous), so those are unaffected.
 */
final class SceneTarget extends RenderTarget {
    enum Hand {
        /** World phase: the real depth. */
        NONE,
        /** After the level: the next depth clear is the hand phase's own, it gets the real depth. */
        ARMED,
        /** Hand and viewmodel are drawn: later clears go to the stand-in. */
        DRAWN
    }

    Hand hand = Hand.NONE;
    @Nullable
    private GpuTexture decoy;

    SceneTarget(int width, int height, boolean stencil) {
        super("BlockLand scene", true);
        resize(width, height);
        if (stencil) enableStencil();
    }

    /** The depth texture itself, whatever the phase. */
    GpuTexture depth() {
        return depthTexture;
    }

    @Override
    public GpuTexture getDepthTexture() {
        switch (hand) {
            case ARMED -> {
                hand = Hand.DRAWN;
                return depthTexture;
            }
            case DRAWN -> {
                return decoy();
            }
            default -> {
                return depthTexture;
            }
        }
    }

    private GpuTexture decoy() {
        if (decoy == null || decoy.isClosed()) {
            decoy = RenderSystem.getDevice().createTexture(() -> "BlockLand scene depth stand-in",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.DEPTH32, 1, 1, 1, 1);
        }
        return decoy;
    }

    void close() {
        destroyBuffers();
        if (decoy != null) decoy.close();
        decoy = null;
    }
}
