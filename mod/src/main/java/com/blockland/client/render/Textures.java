package com.blockland.client.render;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Textures used by retained models: fallbacks (white, a flat normal), PNGs from resource packs (skin patterns,
 * effects) and LabPBR texture sets (asset spec §20: albedo, {@code _n} normal / AO / height, {@code _s} smoothness /
 * F0 / emission). Everything is uploaded once with a full mip chain built on the CPU (tiling HD textures would shimmer
 * without one) and kept until the next resource reload.
 */
public final class Textures {
    /** How a mip level is averaged from the one above. */
    public enum Kind {
        /** sRGB colour: averaged in linear light so dark/bright patterns keep their brightness. */
        COLOR,
        /** Data stored linearly (normals, AO, height): plain average. */
        DATA,
        /** LabPBR {@code _s}: metal ids (G ≥ 230) and "no emission" (A = 255) are codes, not values. */
        SPECULAR
    }

    /**
     * A loaded texture set: the albedo always, normal and specular when the files exist.
     *
     * @param mean the albedo's average display colour (alpha-weighted): a legacy tint recolours the set by
     *             {@code tint / mean}, which keeps the texture's detail around the tint
     */
    public record TextureSet(ResourceLocation albedoLocation, GpuTextureView albedo, @Nullable GpuTextureView normal,
                             @Nullable GpuTextureView specular, float[] mean) {
    }

    private record Owned(GpuTexture texture, GpuTextureView view, float[] mean) {
        Owned(GpuTexture texture, GpuTextureView view) {
            this(texture, view, new float[]{1, 1, 1});
        }

        void close() {
            view.close();
            texture.close();
        }
    }

    private static Owned white;
    private static Owned flatNormal;
    private static Owned noSpecular;
    private static final Map<ResourceLocation, Optional<Owned>> resources = new HashMap<>();
    private static final Map<String, Optional<TextureSet>> sets = new HashMap<>();
    private static final Map<GpuTextureView, ResourceLocation> locations = new IdentityHashMap<>();
    private static final List<Owned> setTextures = new ArrayList<>();
    private static final Set<String> warned = new HashSet<>();

    private Textures() {
    }

    // ---- fallbacks ------------------------------------------------------------------------------

    public static GpuTextureView white() {
        if (white == null) white = solid("blockland white", 0xFFFFFFFF);
        return white.view();
    }

    /** A flat LabPBR normal: no tilt, no AO, top height. */
    public static GpuTextureView flatNormal() {
        if (flatNormal == null) flatNormal = solid("blockland flat normal", 0xFFFF8080);
        return flatNormal.view();
    }

    /** Bound where a draw has no {@code _s} (the shader then ignores it). */
    public static GpuTextureView noSpecular() {
        if (noSpecular == null) noSpecular = solid("blockland no specular", 0xFF000000);
        return noSpecular.view();
    }

    private static Owned solid(String label, int abgr) {
        try (NativeImage image = new NativeImage(1, 1, false)) {
            image.setPixelABGR(0, 0, abgr);
            GpuTexture texture = upload(label, image, true, Kind.DATA);
            return new Owned(texture, RenderSystem.getDevice().createTextureView(texture));
        }
    }

    // ---- resource textures ------------------------------------------------------------------------

    /** A PNG from the resource packs, uploaded once (tiling, mipmapped); null when missing. */
    @Nullable
    public static GpuTextureView resource(ResourceLocation location) {
        return resources.computeIfAbsent(location, Textures::loadColor).map(Owned::view).orElse(null);
    }

    /** The resource location a view from {@link #resource} or a texture set was loaded from, or null. */
    @Nullable
    public static ResourceLocation locationOf(@Nullable GpuTextureView view) {
        return view == null ? null : locations.get(view);
    }

    /** The GPU view of a texture reference (a resource PNG, or a TextureManager texture such as a player skin). */
    @Nullable
    public static GpuTextureView view(@Nullable TextureRef ref) {
        if (ref == null) return null;
        if (!ref.managed()) return resource(ref.location());
        try {
            return Minecraft.getInstance().getTextureManager().getTexture(ref.location()).getTextureView();
        } catch (RuntimeException e) {
            warnOnce(ref.location().toString(), e);
            return null;
        }
    }

    private static Optional<Owned> loadColor(ResourceLocation location) {
        Owned owned = load(location, Kind.COLOR);
        if (owned != null) locations.put(owned.view(), location);
        return Optional.ofNullable(owned);
    }

    // ---- texture sets -----------------------------------------------------------------------------

    /**
     * A LabPBR texture set by its base path under {@code assets/blockland/} (no {@code .png}), e.g.
     * {@code textures/pbr/ak47/wood}; null when the albedo is missing (the material keeps its own look then).
     */
    @Nullable
    public static TextureSet set(@Nullable String base) {
        if (base == null || base.isEmpty()) return null;
        return sets.computeIfAbsent(base, Textures::loadSet).orElse(null);
    }

    private static Optional<TextureSet> loadSet(String base) {
        ResourceLocation albedo, normal, specular;
        try {
            albedo = BlockLand.id(base + ".png");
            normal = BlockLand.id(base + "_n.png");
            specular = BlockLand.id(base + "_s.png");
        } catch (RuntimeException e) {
            warnOnce(base, e);
            return Optional.empty();
        }
        Owned a = load(albedo, Kind.COLOR);
        if (a == null) {
            warnOnce(base, null);
            return Optional.empty();
        }
        Owned n = exists(normal) ? load(normal, Kind.DATA) : null;
        Owned s = exists(specular) ? load(specular, Kind.SPECULAR) : null;
        for (Owned owned : new Owned[]{a, n, s}) if (owned != null) setTextures.add(owned);
        locations.put(a.view(), albedo);
        return Optional.of(new TextureSet(albedo, a.view(), n == null ? null : n.view(), s == null ? null : s.view(), a.mean()));
    }

    private static boolean exists(ResourceLocation location) {
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    @Nullable
    private static Owned load(ResourceLocation location, Kind kind) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            float[] mean = kind == Kind.COLOR ? mean(image.getPixelsABGR()) : new float[]{1, 1, 1};
            GpuTexture texture = upload(location.toString(), image, true, kind);
            return new Owned(texture, RenderSystem.getDevice().createTextureView(texture), mean);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot load texture {}", location, e);
            return null;
        }
    }

    /** Average display colour of ABGR pixels, weighted by alpha (cut-out texels do not count). */
    static float[] mean(int[] abgr) {
        double r = 0, g = 0, b = 0, weight = 0;
        for (int c : abgr) {
            double w = (c >>> 24 & 0xFF) / 255.0;
            r += (c & 0xFF) * w;
            g += (c >>> 8 & 0xFF) * w;
            b += (c >>> 16 & 0xFF) * w;
            weight += w;
        }
        if (weight <= 0) return new float[]{1, 1, 1};
        return new float[]{(float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255)};
    }

    private static void warnOnce(String what, @Nullable Exception e) {
        if (!warned.add(what)) return;
        if (e == null) BlockLand.LOGGER.warn("[BlockLand] Missing texture set {} (albedo PNG not found)", what);
        else BlockLand.LOGGER.warn("[BlockLand] Cannot use texture {}: {}", what, e.toString());
    }

    // ---- upload with mipmaps ------------------------------------------------------------------------

    /**
     * Uploads an RGBA image with a full mip chain (LINEAR + mipmaps; {@code repeat} for tiling patterns).
     */
    public static GpuTexture upload(String label, NativeImage image, boolean repeat, Kind kind) {
        int width = image.getWidth(), height = image.getHeight();
        // Blaze3D sizes level n as size >> n without clamping to 1, so the chain stops where the short side would vanish
        int levels = 1;
        while ((Math.min(width, height) >> levels) > 0) levels++;
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, width, height, 1, levels);
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.writeToTexture(texture, image, 0, 0, 0, 0, width, height, 0, 0);
        int[] pixels = image.getPixelsABGR();
        int w = width, h = height;
        for (int level = 1; level < levels; level++) {
            int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
            pixels = Mipmaps.down(pixels, w, h, nw, nh, kind);
            try (NativeImage mip = new NativeImage(nw, nh, false)) {
                for (int y = 0; y < nh; y++) {
                    for (int x = 0; x < nw; x++) mip.setPixelABGR(x, y, pixels[y * nw + x]);
                }
                encoder.writeToTexture(texture, mip, level, 0, 0, 0, nw, nh, 0, 0);
            }
            w = nw;
            h = nh;
        }
        texture.setAddressMode(repeat ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE);
        texture.setTextureFilter(FilterMode.LINEAR, levels > 1);
        return texture;
    }

    /** Resource reload: drop everything, it is reloaded on demand. */
    public static void clear() {
        resources.values().forEach(o -> o.ifPresent(Owned::close));
        setTextures.forEach(Owned::close);
        resources.clear();
        sets.clear();
        setTextures.clear();
        locations.clear();
        warned.clear();
        VanillaDraws.clear();
    }
}
