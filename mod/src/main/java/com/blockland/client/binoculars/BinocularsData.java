package com.blockland.client.binoculars;

import com.blockland.BlockLand;
import com.blockland.client.scope.HandOptic;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The binoculars as written in {@code assets/blockland/binoculars/binoculars.json} (read by Gson; asset spec §52): how
 * long raising and lowering take, and the optic itself ({@link HandOptic}: powers, sway, the view, the rangefinder).
 * Resource packs can override it; reloaded with F3+T.
 */
final class BinocularsData {
    private static final ResourceLocation FILE = BlockLand.id("binoculars/binoculars.json");
    private static BinocularsData loaded;

    /** Seconds from lowered to at the eyes, and back. */
    float raiseSeconds = 0.32f;
    float lowerSeconds = 0.22f;
    HandOptic optic = new HandOptic();

    static BinocularsData get() {
        if (loaded == null) loaded = load();
        return loaded;
    }

    /** A resource reload: read the file again when next needed. */
    static void reset() {
        loaded = null;
    }

    private static BinocularsData load() {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return new BinocularsData();
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            BinocularsData data = new Gson().fromJson(reader, BinocularsData.class);
            if (data == null) return new BinocularsData();
            if (data.optic == null) data.optic = new HandOptic();
            return data;
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
            return new BinocularsData();
        }
    }
}
