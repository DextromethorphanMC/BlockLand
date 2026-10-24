package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.render.LightSources;
import com.blockland.client.render.SceneLighting;
import com.google.gson.Gson;
import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The frame's dynamic lights (rendering spec §5): what features added through {@link LightSources}, light items held by
 * players and mobs or lying on the ground ({@code graphics/item_lights.json}), and burning entities. The
 * {@link SceneLighting#MAX_LIGHTS} that matter most for the camera — bright, large and near — go into the scene block,
 * camera-relative, in linear light.
 */
final class DynamicLights {
    private static final ResourceLocation FILE = BlockLand.id("graphics/item_lights.json");
    /** Entities further than this from the camera are not looked at for light items. */
    private static final double ENTITY_RANGE = 48;
    /** A light this much further than its own radius from the camera cannot touch anything seen. */
    private static final double SLACK = 48;
    private static final LightSources.Light FIRE = new LightSources.Light(0, 0, 0, 1f, 0.55f, 0.22f, 1.4f, 9f);

    /** One item's light (Gson; field names = JSON keys). */
    static final class ItemLight {
        String color = "#FFFFFF";
        float intensity = 1;
        float radius = 10;
        transient float r = 1, g = 1, b = 1;
    }

    private static final class File {
        Map<String, ItemLight> lights = Map.of();
    }

    private record Candidate(LightSources.Light light, double score) {
    }

    private Map<ResourceLocation, ItemLight> items = Map.of();
    private final List<Candidate> candidates = new ArrayList<>();
    /** The chosen lights: camera-relative position and radius, linear colour × intensity. */
    private final float[] data = new float[SceneLighting.MAX_LIGHTS * 8];
    private int count;

    void load(ResourceManager manager) {
        items = Map.of();
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            File file = new Gson().fromJson(reader, File.class);
            Map<ResourceLocation, ItemLight> loaded = new HashMap<>();
            if (file != null && file.lights != null) file.lights.forEach((name, light) -> {
                ResourceLocation id = ResourceLocation.tryParse(name);
                if (id == null || light == null) return;
                int rgb = parse(light.color);
                light.r = (rgb >> 16 & 0xFF) / 255f;
                light.g = (rgb >> 8 & 0xFF) / 255f;
                light.b = (rgb & 0xFF) / 255f;
                loaded.put(id, light);
            });
            items = Map.copyOf(loaded);
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
        }
    }

    int count() {
        return count;
    }

    /** Gathers this frame's lights around the camera and keeps the strongest. */
    void collect(@Nullable ClientLevel level, double camX, double camY, double camZ, float partial, GraphicsSettings settings) {
        candidates.clear();
        count = 0;
        if (level == null || !settings.dynamicLights) {
            LightSources.collect(light -> { }); // keeps the flash list from growing while nothing reads it
            return;
        }
        LightSources.collect(light -> offer(light, camX, camY, camZ));
        if (settings.heldItemLights) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity.isRemoved() || entity.distanceToSqr(camX, camY, camZ) > ENTITY_RANGE * ENTITY_RANGE) continue;
                Vec3 at = entity.getPosition(partial);
                if (entity instanceof LivingEntity living) {
                    double y = at.y + living.getBbHeight() * 0.62;
                    held(living.getMainHandItem(), at.x, y, at.z, camX, camY, camZ);
                    held(living.getOffhandItem(), at.x, y, at.z, camX, camY, camZ);
                } else if (entity instanceof ItemEntity item) {
                    held(item.getItem(), at.x, at.y + 0.25, at.z, camX, camY, camZ);
                }
                if (entity.displayFireAnimation()) {
                    offer(new LightSources.Light(at.x, at.y + entity.getBbHeight() * 0.5, at.z, FIRE.r(), FIRE.g(), FIRE.b(),
                            FIRE.intensity(), FIRE.radius()), camX, camY, camZ);
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        for (Candidate candidate : candidates) {
            if (count == SceneLighting.MAX_LIGHTS) break;
            LightSources.Light l = candidate.light;
            int i = count * 8;
            data[i] = (float) (l.x() - camX);
            data[i + 1] = (float) (l.y() - camY);
            data[i + 2] = (float) (l.z() - camZ);
            data[i + 3] = l.radius();
            data[i + 4] = linear(l.r()) * l.intensity();
            data[i + 5] = linear(l.g()) * l.intensity();
            data[i + 6] = linear(l.b()) * l.intensity();
            data[i + 7] = 0;
            count++;
        }
    }

    /** BlLights of the scene block (all slots; unused ones zero). */
    void write(Std140Builder out) {
        for (int i = 0; i < SceneLighting.MAX_LIGHTS; i++) {
            if (i < count) {
                int k = i * 8;
                out.putVec4(data[k], data[k + 1], data[k + 2], data[k + 3]);
                out.putVec4(data[k + 4], data[k + 5], data[k + 6], data[k + 7]);
            } else {
                out.putVec4(0, 0, 0, 0);
                out.putVec4(0, 0, 0, 0);
            }
        }
    }

    private void held(ItemStack stack, double x, double y, double z, double camX, double camY, double camZ) {
        if (stack.isEmpty()) return;
        ItemLight light = items.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (light == null) return;
        offer(new LightSources.Light(x, y, z, light.r, light.g, light.b, light.intensity, light.radius), camX, camY, camZ);
    }

    private void offer(LightSources.Light light, double camX, double camY, double camZ) {
        if (light.intensity() <= 0.001f || light.radius() <= 0.01f) return;
        double dx = light.x() - camX, dy = light.y() - camY, dz = light.z() - camZ;
        double distance2 = dx * dx + dy * dy + dz * dz;
        double reach = light.radius() + SLACK;
        if (distance2 > reach * reach) return;
        // bright, large and near first: what lights most of what the camera sees
        double score = light.intensity() * light.radius() * light.radius() / Math.max(1, distance2);
        candidates.add(new Candidate(light, score));
    }

    private static float linear(float display) {
        return (float) Math.pow(Math.max(0, display), 2.2);
    }

    private static int parse(@Nullable String hex) {
        if (hex == null) return 0xFFFFFF;
        String digits = hex.trim().startsWith("#") ? hex.trim().substring(1) : hex.trim();
        try {
            return digits.length() == 6 ? Integer.parseInt(digits, 16) : 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
