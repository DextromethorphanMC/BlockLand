package com.blockland.client.viewmodel;

import com.blockland.BlockLand;
import com.blockland.client.render.GpuParticles;
import com.blockland.client.render.Textures;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * First-person particle effects by name (asset spec §38): {@code assets/blockland/viewmodel/particles.json} maps an id
 * ({@code cerberus.sparks} …) to what one GPU emitter does — how many, how long they live, how they fly, how big and
 * what colour ({@link GpuParticles.Emitter}). Skins name these presets in their cues and streams; resource packs can
 * override them; reloaded with F3+T.
 */
public final class ParticlePresets {
    private static final ResourceLocation FILE = BlockLand.id("viewmodel/particles.json");
    @Nullable
    private static Map<String, GpuParticles.Emitter> presets;

    /** One preset as written (Gson; field names = JSON keys, see {@link GpuParticles.Emitter} for their meaning). */
    private static final class Preset {
        int count = 24;
        float life = 0.6f, lifeJitter = 0.3f, spawn = 0, speed = 0.5f, speedJitter = 0.5f, drag = 2, gravity = 0;
        float radius = 0.005f, direction = 0, orbit = 0, gather = 0, turbulence = 0;
        float[] size = {0.003f, 0.001f};
        float stretch = 0, intensity = 1;
        String color = "#FFFFFF";
        boolean loop, ring;
        String texture = "";

        GpuParticles.Emitter emitter() {
            float start = size != null && size.length > 0 ? size[0] : 0.003f;
            float end = size != null && size.length > 1 ? size[1] : start;
            return new GpuParticles.Emitter(Math.min(GpuParticles.MAX, Math.max(1, count)), life, lifeJitter, spawn, speed,
                    speedJitter, drag, gravity, radius, direction, orbit, gather, turbulence, start, end, stretch, rgb(color),
                    intensity, loop, ring, texture(texture));
        }
    }

    private ParticlePresets() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    presets = null;
                    GpuParticles.clear();
                }));
    }

    /** The emitter of a preset, null when there is none of that name. */
    @Nullable
    public static GpuParticles.Emitter get(String id) {
        if (presets == null) presets = load();
        return presets.get(id);
    }

    private static Map<String, GpuParticles.Emitter> load() {
        Map<String, GpuParticles.Emitter> loaded = new HashMap<>();
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return loaded;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            Map<String, Preset> file = new Gson().fromJson(reader, new TypeToken<Map<String, Preset>>() { }.getType());
            if (file != null) file.forEach((id, preset) -> {
                if (preset != null) loaded.put(id, preset.emitter());
            });
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
        }
        return loaded;
    }

    private static int rgb(@Nullable String hex) {
        if (hex == null) return 0xFFFFFF;
        String digits = hex.trim().startsWith("#") ? hex.trim().substring(1) : hex.trim();
        try {
            return digits.length() == 6 ? Integer.parseInt(digits, 16) : 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    @Nullable
    private static GpuTextureView texture(@Nullable String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Textures.resource(BlockLand.id(path.trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
