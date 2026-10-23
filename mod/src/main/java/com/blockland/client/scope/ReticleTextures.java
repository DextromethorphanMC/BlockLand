package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reticle textures for the eyepiece, mipmapped: a reticle is authored large (1024 px) and drawn a few hundred pixels
 * wide in the eyepiece, where its thin lines would shimmer without mip levels. Loaded on first use from the resource
 * packs, dropped on a resource reload.
 */
final class ReticleTextures {
    private static final int MIP_LEVELS = 5;
    private static final Map<ResourceLocation, Optional<Loaded>> loaded = new HashMap<>();
    /** Reticles registered with the TextureManager for GUI blits (the zoom fallback's overlay). */
    private static final Set<ResourceLocation> registered = new HashSet<>();

    private record Loaded(GpuTexture texture, GpuTextureView view) {
    }

    private ReticleTextures() {
    }

    /** The reticle at {@code path} under {@code assets/blockland/}, or null when it is missing or broken. */
    @Nullable
    static GpuTextureView get(String path) {
        if (path == null || path.isEmpty()) return null;
        return loaded.computeIfAbsent(BlockLand.id(path), ReticleTextures::load).map(Loaded::view).orElse(null);
    }

    /**
     * The same mipmapped reticle as a TextureManager texture, for GUI blits (the classic zoom's overlay); null when it
     * is missing. The TextureManager entry shares the texture and never closes it ({@link #clear} does).
     */
    @Nullable
    static ResourceLocation gui(String path) {
        if (path == null || path.isEmpty()) return null;
        Optional<Loaded> reticle = loaded.computeIfAbsent(BlockLand.id(path), ReticleTextures::load);
        if (reticle.isEmpty()) return null;
        ResourceLocation id = BlockLand.id("gui_reticle/" + path.toLowerCase(Locale.ROOT));
        if (registered.add(id)) Minecraft.getInstance().getTextureManager().register(id, new Shared(reticle.get()));
        return id;
    }

    /** A TextureManager view of a loaded reticle. */
    private static final class Shared extends AbstractTexture {
        Shared(Loaded reticle) {
            this.texture = reticle.texture();
            this.textureView = reticle.view();
        }

        @Override
        public void close() {
            // owned by ReticleTextures
        }
    }

    private static Optional<Loaded> load(ResourceLocation location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing reticle {}", location);
            return Optional.empty();
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int levels = Math.max(1, Math.min(MIP_LEVELS, 31 - Integer.numberOfLeadingZeros(Math.min(image.getWidth(), image.getHeight()))));
            NativeImage[] mips = MipmapGenerator.generateMipLevels(new NativeImage[]{image}, levels - 1);
            GpuDevice device = RenderSystem.getDevice();
            GpuTexture texture = device.createTexture(location.toString(), GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8, image.getWidth(), image.getHeight(), 1, levels);
            CommandEncoder encoder = device.createCommandEncoder();
            for (int level = 0; level < levels; level++) {
                NativeImage mip = mips[level];
                encoder.writeToTexture(texture, mip, level, 0, 0, 0, mip.getWidth(), mip.getHeight(), 0, 0);
                if (level > 0) mip.close();
            }
            texture.setTextureFilter(FilterMode.LINEAR, true);
            texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            return Optional.of(new Loaded(texture, device.createTextureView(texture)));
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot load reticle {}", location, e);
            return Optional.empty();
        }
    }

    /** Resource reload: everything is loaded again on demand. */
    static void clear() {
        registered.forEach(id -> Minecraft.getInstance().getTextureManager().release(id));
        registered.clear();
        loaded.values().forEach(entry -> entry.ifPresent(l -> {
            l.view().close();
            l.texture().close();
        }));
        loaded.clear();
    }
}
