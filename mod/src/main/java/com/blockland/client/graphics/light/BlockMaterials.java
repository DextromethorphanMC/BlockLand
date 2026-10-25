package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.render.PlantWind;
import com.blockland.client.render.SceneLighting;
import com.google.gson.Gson;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Block materials (rendering spec §7, {@code graphics/block_materials.json}): which block textures glow, which are water,
 * glass or polished metal, and how the wind moves the plants (§18). After every atlas stitch they are resolved to the block
 * atlas: a material map (one texel per atlas cell, sampled by the lighting pack's terrain shaders on unit 13; B packs the
 * kind and the wind bits, see bl_lighting.glsl) and the first {@link SceneLighting#MAX_MATERIALS}
 * sprite rectangles for the scene block.
 */
final class BlockMaterials {
    private static final ResourceLocation FILE = BlockLand.id("graphics/block_materials.json");
    /** The map never grows beyond this many cells per side (atlases with odd sprite sizes share cells instead). */
    private static final int MAX_MAP = 2048;

    /** One entry of the file (Gson; field names = JSON keys). */
    static final class Entry {
        List<String> textures = List.of();
        float emission;
        float threshold;
        String kind = "plain";
        float reflectivity;
        /** How the wind moves the sprite's plant (rendering spec §18); an entry with only this sets no material. */
        @Nullable
        WindEntry wind;

        boolean material() {
            return wind == null || emission > 0 || threshold > 0 || reflectivity > 0 || kind(kind) != 0;
        }
    }

    /** {@code "wind": {"mode": "bend" | "sway" | "leaves", "flex": 0.5..2, "upper": bool, "water": bool}}. */
    static final class WindEntry {
        String mode = "bend";
        float flex = 1;
        /** The upper half of a two-block plant: it bends on from where the lower half's top went. */
        boolean upper;
        /** A water plant: swayed by a slow current, not by the wind. */
        boolean water;

        /** Bits 2-7 of the map's B byte (bl_lighting.glsl): mode, upper half, water, flex level. */
        int bits() {
            int mode = switch (this.mode == null ? "" : this.mode.trim().toLowerCase(Locale.ROOT)) {
                case "bend" -> 1;
                case "sway" -> 2;
                case "leaves" -> 3;
                default -> 0;
            };
            if (mode == 0) return 0;
            int flexLevel = Math.max(0, Math.min(3, Math.round(flex * 2) - 1));
            return (mode | (upper ? 4 : 0) | (water ? 8 : 0) | flexLevel << 4) << 2;
        }
    }

    private static final class File {
        List<Entry> materials = List.of();
    }

    /** A resolved sprite: atlas uv rect and its parameters. */
    private record Rect(float u0, float v0, float u1, float v1, Entry entry, int kind) {
    }

    private List<Entry> entries = List.of();
    private final List<Rect> rects = new ArrayList<>();
    @Nullable
    private GpuTexture map;
    @Nullable
    private GpuTexture empty;
    /** The atlas texture the map was built for (a stitch brings a new one). */
    @Nullable
    private GpuTexture builtFor;
    private int atlasWidth, atlasHeight;

    /** Reads the file (resource reload). */
    void load(ResourceManager manager) {
        entries = List.of();
        builtFor = null;
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            File file = new Gson().fromJson(reader, File.class);
            if (file != null && file.materials != null) entries = List.copyOf(file.materials);
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
        }
    }

    /** Rebuilds the map when the block atlas was stitched again; returns the texture to bind on unit 13. */
    GpuTexture resolve() {
        TextureAtlas atlas;
        GpuTexture atlasTexture;
        try {
            // through the model manager: asking the texture manager before the first load would register a stand-in
            atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            atlasTexture = atlas.getTexture();
        } catch (RuntimeException e) {
            return empty();
        }
        if (atlasTexture == builtFor && map != null && !map.isClosed()) return map;
        builtFor = atlasTexture;
        build(atlas, atlasTexture.getWidth(0), atlasTexture.getHeight(0));
        return map != null ? map : empty();
    }

    private void build(TextureAtlas atlas, int width, int height) {
        rects.clear();
        atlasWidth = width;
        atlasHeight = height;
        // cells: the largest size every sprite's position and size are multiples of (16 for vanilla's atlas)
        int cell = 0;
        for (ResourceLocation id : atlas.getTextureLocations()) {
            TextureAtlasSprite sprite = atlas.getSprite(id);
            cell = gcd(gcd(gcd(gcd(cell, sprite.getX()), sprite.getY()), sprite.contents().width()), sprite.contents().height());
        }
        cell = Math.max(1, cell);
        while (width / cell > MAX_MAP || height / cell > MAX_MAP) cell *= 2;
        int mapWidth = Math.max(1, width / cell), mapHeight = Math.max(1, height / cell);

        try (NativeImage image = new NativeImage(mapWidth, mapHeight, true)) {
            // materials first, then the wind bits of plant sprites over them (B byte: kind in bits 0-1, the wind above)
            for (Entry entry : entries) {
                if (!entry.material()) continue;
                int kind = kind(entry.kind);
                for (TextureAtlasSprite sprite : sprites(atlas, entry)) {
                    int abgr = byteOf(entry.reflectivity) << 24 | kind << 16 | byteOf(entry.threshold) << 8 | byteOf(entry.emission);
                    fill(image, sprite, cell, mapWidth, mapHeight, abgr, 0xFFFFFFFF);
                    rects.add(new Rect(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), entry, kind));
                }
            }
            Map<ResourceLocation, Integer> windBits = new HashMap<>();
            for (Entry entry : entries) {
                int wind = entry.wind == null ? 0 : entry.wind.bits();
                if (wind == 0) continue;
                for (TextureAtlasSprite sprite : sprites(atlas, entry)) {
                    fill(image, sprite, cell, mapWidth, mapHeight, wind << 16, 0xFC << 16);
                    windBits.put(sprite.contents().name(), wind >> 2);
                }
            }
            // the same bits for the CPU: what must move with a plant (blood on it) asks client/render/PlantWind
            PlantWind.publish(windBits);
            if (map != null) map.close();
            map = RenderSystem.getDevice().createTexture(() -> "BlockLand block materials", GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, mapWidth, mapHeight, 1, 1);
            map.setTextureFilter(FilterMode.NEAREST, false);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(map, image);
        } catch (RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot build the block material map", e);
            map = null;
        }
    }

    private static List<TextureAtlasSprite> sprites(TextureAtlas atlas, Entry entry) {
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        for (String name : entry.textures == null ? List.<String>of() : entry.textures) {
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id != null && atlas.getTextureLocations().contains(id)) sprites.add(atlas.getSprite(id));
        }
        return sprites;
    }

    /** Writes {@code abgr} into the sprite's cells, only the bits of {@code mask} (the rest of the texel is kept). */
    private static void fill(NativeImage image, TextureAtlasSprite sprite, int cell, int mapWidth, int mapHeight, int abgr, int mask) {
        int x0 = sprite.getX() / cell, y0 = sprite.getY() / cell;
        int x1 = Math.min(mapWidth, Math.max(x0 + 1, (sprite.getX() + sprite.contents().width()) / cell));
        int y1 = Math.min(mapHeight, Math.max(y0 + 1, (sprite.getY() + sprite.contents().height()) / cell));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) image.setPixelABGR(x, y, ARGB.toABGR(image.getPixel(x, y)) & ~mask | abgr & mask);
        }
    }

    /** BlMaterialInfo and BlMaterials of the scene block. */
    void write(Std140Builder out) {
        int count = Math.min(rects.size(), SceneLighting.MAX_MATERIALS);
        out.putVec4(count, atlasWidth, atlasHeight, 0);
        for (int i = 0; i < SceneLighting.MAX_MATERIALS; i++) {
            if (i < count) {
                Rect r = rects.get(i);
                out.putVec4(r.u0, r.v0, r.u1, r.v1);
                out.putVec4(r.entry.emission, r.entry.threshold, r.kind, r.entry.reflectivity);
            } else {
                out.putVec4(0, 0, 0, 0);
                out.putVec4(0, 0, 0, 0);
            }
        }
    }

    /** A 1×1 map with no materials (before the atlas exists). */
    GpuTexture empty() {
        if (empty == null || empty.isClosed()) {
            empty = RenderSystem.getDevice().createTexture(() -> "BlockLand block materials (none)", GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, 1, 1, 1, 1);
            empty.setTextureFilter(FilterMode.NEAREST, false);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(empty, image);
            }
        }
        return empty;
    }

    private static int kind(@Nullable String name) {
        if (name == null) return 0;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "water" -> 1;
            case "glass" -> 2;
            case "metal" -> 3;
            default -> 0;
        };
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }
}
