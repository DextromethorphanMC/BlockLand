package com.blockland.client.decal;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decal types from {@code assets/blockland/decals.json} (resource packs can override it; reloaded with F3+T):
 * texture strip, size, how long each decal lives and how many may exist at once (asset spec §14).
 */
final class DecalTypes {
    private static final ResourceLocation FILE = BlockLand.id("decals.json");
    @Nullable
    private static Map<String, Type> types;

    /** One decal type (Gson; field names = JSON keys). */
    static final class Type {
        String id;
        /** Texture path under {@code assets/blockland/}: {@code variants} frames side by side. */
        String texture = "";
        int variants = 1;
        /** Width (along the cut) and height in blocks. */
        float[] size = {0.1f, 0.1f};
        /** Random size change per decal, as a fraction (0.2 = ±20 %). */
        float sizeJitter = 0;
        /** Seconds each decal lives (including its fade)… */
        float lifetime = 20;
        /** …plus or minus up to this many seconds, so a burst of holes does not vanish at once. */
        float lifetimeJitter = 0;
        /** Seconds of fading out at the end of its life. */
        float fade = 2;
        /** At most this many at once; the oldest fades out quickly to make room. */
        int max = 128;
        /** Opacity multiplier. */
        float opacity = 1;
        transient ResourceLocation textureLocation;
    }

    private DecalTypes() {
    }

    @Nullable
    static Type get(String id) {
        if (types == null) types = load();
        return types.get(id);
    }

    static void clear() {
        types = null;
    }

    private static Map<String, Type> load() {
        Map<String, Type> loaded = new LinkedHashMap<>();
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return loaded;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            Map<String, Type> file = new Gson().fromJson(reader, new TypeToken<Map<String, Type>>() { }.getType());
            if (file != null) file.forEach((id, type) -> {
                type.id = id;
                type.variants = Math.max(1, type.variants);
                type.textureLocation = BlockLand.id(type.texture);
                loaded.put(id, type);
            });
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
        }
        return loaded;
    }
}
