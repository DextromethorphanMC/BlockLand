package com.blockland.client.thirdperson;

import com.blockland.BlockLand;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The box of a held (low-poly) item model in its own units (16 per block): the space its display transforms were tuned
 * for, which the 3D weapon is fitted into (asset spec §37). Read from the model's elements (a skin variant's parent's),
 * element rotations ignored. Cached until the next resource reload.
 */
record HeldBox(Vector3fc min, Vector3fc max) {
    /** A model without elements (or none at all): the whole block. */
    static final HeldBox BLOCK = new HeldBox(new Vector3f(), new Vector3f(16));

    private static final Map<ResourceLocation, HeldBox> cache = new HashMap<>();

    static HeldBox of(@Nullable ResourceLocation model) {
        if (model == null) return BLOCK;
        return cache.computeIfAbsent(model, HeldBox::load);
    }

    static void clear() {
        cache.clear();
    }

    Vector3f centre(Vector3f into) {
        return into.set(min).add(max).mul(0.5f);
    }

    Vector3f size(Vector3f into) {
        return into.set(max).sub(min);
    }

    private static HeldBox load(ResourceLocation model) {
        ResourceLocation at = model;
        try {
            for (int depth = 0; depth < 4 && at != null; depth++) {
                JsonObject json = read(at);
                if (json == null) break;
                if (json.has("elements")) return box(json.getAsJsonArray("elements"));
                at = json.has("parent") ? ResourceLocation.tryParse(json.get("parent").getAsString()) : null;
            }
        } catch (RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot read the held model {} for third person: {}", model, e.toString());
        }
        return BLOCK;
    }

    @Nullable
    private static JsonObject read(ResourceLocation model) {
        ResourceLocation file = model.withPath(p -> "models/" + p + ".json");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
        if (resource.isEmpty()) return null;
        try (Reader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Cannot read {}: {}", file, e.toString());
            return null;
        }
    }

    private static HeldBox box(JsonArray elements) {
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
        for (JsonElement element : elements) {
            JsonObject e = element.getAsJsonObject();
            Vector3f from = vector(e.getAsJsonArray("from")), to = vector(e.getAsJsonArray("to"));
            min.min(from).min(to);
            max.max(from).max(to);
        }
        return min.x > max.x ? BLOCK : new HeldBox(min, max);
    }

    private static Vector3f vector(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
}
