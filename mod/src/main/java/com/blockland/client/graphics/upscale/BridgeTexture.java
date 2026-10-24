package com.blockland.client.graphics.upscale;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * A GL texture the DLSS bridge made (over Vulkan memory), as a Blaze3D texture so full-screen passes can draw into it and
 * sample it. Blaze3D deletes the GL name (and its framebuffers) when this closes, so it must close before the bridge frees
 * its textures.
 */
final class BridgeTexture {
    final GpuTexture texture;
    final GpuTextureView view;

    BridgeTexture(String label, int glTexture, int width, int height) {
        texture = new Foreign(label, glTexture, width, height);
        texture.setTextureFilter(FilterMode.LINEAR, false);
        texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
        view = RenderSystem.getDevice().createTextureView(texture);
    }

    void close() {
        view.close();
        texture.close();
    }

    /** A Blaze3D texture over an existing GL texture name (its real format is the bridge's; Blaze3D only uses the name). */
    private static final class Foreign extends GlTexture {
        Foreign(String label, int id, int width, int height) {
            super(GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, label, TextureFormat.RGBA8, width, height, 1, 1, id);
        }
    }
}
