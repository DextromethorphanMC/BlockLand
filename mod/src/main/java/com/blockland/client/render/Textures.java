package com.blockland.client.render;

import com.blockland.BlockLand;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
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
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.GLCapabilities;

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
 * without one) and kept until the next resource reload. A set's {@code _s} mips are roughened by the normal detail they
 * average over (Toksvig), every mipmapped texture is filtered anisotropically, and their mip bias follows the frame's
 * ({@link FrameJitter#mipBias}, kept by {@link #syncSampling}).
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
    /** Every mipmapped texture uploaded here (sets, resource PNGs, models' own images), for {@link #syncSampling}. */
    private static final List<GpuTexture> mipmapped = new ArrayList<>();
    private static float appliedBias;
    /** The anisotropic filtering used (1 = none), -1 until asked. */
    private static float anisotropy = -1;

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
        Owned n = null, s = null;
        NativeImage normals = exists(normal) ? read(normal) : null;
        try {
            if (normals != null) n = own(normal, normals, Kind.DATA, null);
            // the specular map's mips take the normal map's averaged-away detail as roughness
            s = exists(specular) ? load(specular, Kind.SPECULAR, normals) : null;
        } finally {
            if (normals != null) normals.close();
        }
        for (Owned owned : new Owned[]{a, n, s}) if (owned != null) setTextures.add(owned);
        locations.put(a.view(), albedo);
        return Optional.of(new TextureSet(albedo, a.view(), n == null ? null : n.view(), s == null ? null : s.view(), a.mean()));
    }

    private static boolean exists(ResourceLocation location) {
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    @Nullable
    private static Owned load(ResourceLocation location, Kind kind) {
        return load(location, kind, null);
    }

    /** @param normals the set's normal map when this is its {@code _s} (see {@link #upload}) */
    @Nullable
    private static Owned load(ResourceLocation location, Kind kind, @Nullable NativeImage normals) {
        NativeImage image = read(location);
        if (image == null) return null;
        try (image) {
            return own(location, image, kind, normals);
        } catch (RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot load texture {}", location, e);
            return null;
        }
    }

    @Nullable
    private static NativeImage read(ResourceLocation location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return NativeImage.read(in);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot load texture {}", location, e);
            return null;
        }
    }

    private static Owned own(ResourceLocation location, NativeImage image, Kind kind, @Nullable NativeImage normals) {
        float[] mean = kind == Kind.COLOR ? mean(image.getPixelsABGR()) : new float[]{1, 1, 1};
        GpuTexture texture = upload(location.toString(), image, true, kind, normals);
        return new Owned(texture, RenderSystem.getDevice().createTextureView(texture), mean);
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
        return upload(label, image, repeat, kind, null);
    }

    /**
     * @param normalMap with a {@code _s} map: the set's normal map (same size). Each mip level of the specular map is
     *                  roughened by the normal detail it averages over ({@link Mipmaps#roughen}), so a surface's fine
     *                  relief still breaks up its highlight at a distance instead of turning mirror-flat.
     */
    private static GpuTexture upload(String label, NativeImage image, boolean repeat, Kind kind, @Nullable NativeImage normalMap) {
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
        float[] normals = kind == Kind.SPECULAR && normalMap != null && normalMap.getWidth() == width
                && normalMap.getHeight() == height ? Mipmaps.normals(normalMap.getPixelsABGR()) : null;
        int w = width, h = height;
        for (int level = 1; level < levels; level++) {
            int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
            pixels = Mipmaps.down(pixels, w, h, nw, nh, kind);
            int[] written = pixels;
            if (normals != null) {
                // the next level still averages the plain smoothness: the normals carry all the detail below it
                normals = Mipmaps.downNormals(normals, w, h, nw, nh);
                written = pixels.clone();
                Mipmaps.roughen(written, normals);
            }
            try (NativeImage mip = new NativeImage(nw, nh, false)) {
                for (int y = 0; y < nh; y++) {
                    for (int x = 0; x < nw; x++) mip.setPixelABGR(x, y, written[y * nw + x]);
                }
                encoder.writeToTexture(texture, mip, level, 0, 0, 0, nw, nh, 0, 0);
            }
            w = nw;
            h = nh;
        }
        texture.setAddressMode(repeat ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE);
        texture.setTextureFilter(FilterMode.LINEAR, levels > 1);
        if (levels > 1) {
            mipmapped.add(texture);
            sampling(texture);
        }
        return texture;
    }

    // ---- sampling -----------------------------------------------------------------------------------

    /**
     * Before a frame's retained-model draws: brings every mipmapped texture to the frame's mip bias when it changed
     * ({@link FrameJitter#mipBias}).
     */
    public static void syncSampling() {
        float bias = FrameJitter.mipBias();
        if (bias == appliedBias) return;
        appliedBias = bias;
        mipmapped.removeIf(GpuTexture::isClosed);
        for (GpuTexture texture : mipmapped) sampling(texture);
    }

    /**
     * A mipmapped texture's sampling beyond what Blaze3D sets (filters and wrapping): the strongest anisotropic filtering
     * the driver offers up to 16× (the top of a slide or a barrel is seen at a grazing angle) and the frame's mip bias.
     */
    private static void sampling(GpuTexture texture) {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._bindTexture(((GlTexture) texture).glId());
        if (anisotropy() > 1) GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL46.GL_TEXTURE_MAX_ANISOTROPY, anisotropy());
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, appliedBias);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(unit);
    }

    private static float anisotropy() {
        if (anisotropy < 0) {
            GLCapabilities caps = GL.getCapabilities();
            boolean supported = caps.OpenGL46 || caps.GL_ARB_texture_filter_anisotropic || caps.GL_EXT_texture_filter_anisotropic;
            anisotropy = supported ? Math.max(1, Math.min(16, GL11.glGetFloat(GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY))) : 1;
        }
        return anisotropy;
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
