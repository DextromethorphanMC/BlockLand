package com.blockland.client.render;

import com.blockland.BlockLand;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Textures used by retained models: a white fallback and PNGs from resource packs (skin patterns, effects). */
public final class Textures {
    private static GpuTexture white;
    private static GpuTextureView whiteView;
    private static final Map<ResourceLocation, GpuTextureView> loaded = new HashMap<>();
    private static final Map<ResourceLocation, GpuTexture> owners = new HashMap<>();

    private Textures() {
    }

    public static GpuTextureView white() {
        if (whiteView == null) {
            try (NativeImage image = new NativeImage(1, 1, false)) {
                image.setPixel(0, 0, 0xFFFFFFFF);
                white = GpuModel.texture("blockland white", image, true);
                whiteView = RenderSystem.getDevice().createTextureView(white);
            }
        }
        return whiteView;
    }

    /** A PNG from the resource packs, uploaded once; null when missing. */
    @Nullable
    public static GpuTextureView resource(ResourceLocation location) {
        if (loaded.containsKey(location)) return loaded.get(location);
        GpuTextureView view = null;
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isPresent()) {
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                GpuTexture texture = GpuModel.texture(location.toString(), image, true);
                owners.put(location, texture);
                view = RenderSystem.getDevice().createTextureView(texture);
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Cannot load texture {}", location, e);
            }
        }
        loaded.put(location, view);
        return view;
    }

    /** Resource reload: drop everything, it is reloaded on demand. */
    public static void clear() {
        loaded.values().forEach(v -> { if (v != null) v.close(); });
        owners.values().forEach(GpuTexture::close);
        loaded.clear();
        owners.clear();
    }
}
