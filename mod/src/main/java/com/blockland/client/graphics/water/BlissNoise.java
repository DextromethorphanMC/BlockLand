package com.blockland.client.graphics.water;

import com.blockland.BlockLand;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Bliss's noise texture ({@code noisetex}: Bliss's {@code shaders/texture/noises.png}, shipped byte for byte as
 * {@code assets/blockland/textures/bliss/noises.png}, linear and repeating by its {@code .mcmeta}), which the ported
 * Bliss water (rendering spec §15, {@code blockland:bliss_water.glsl}) samples. Loaded through the texture manager on first
 * use (render thread), so a resource reload brings it back.
 */
public final class BlissNoise {
    public static final ResourceLocation LOCATION = BlockLand.id("textures/bliss/noises.png");

    private BlissNoise() {
    }

    @Nullable
    private static AbstractTexture loaded() {
        try {
            return Minecraft.getInstance().getTextureManager().getTexture(LOCATION);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The texture, or null when it could not be loaded. */
    @Nullable
    public static GpuTexture texture() {
        AbstractTexture texture = loaded();
        GpuTexture gpu = texture == null ? null : texture.getTexture();
        return gpu == null || gpu.isClosed() ? null : gpu;
    }

    /** A view of the texture, or null when it could not be loaded. */
    @Nullable
    public static GpuTextureView view() {
        AbstractTexture texture = loaded();
        GpuTextureView view = texture == null ? null : texture.getTextureView();
        return view == null || view.isClosed() ? null : view;
    }
}
