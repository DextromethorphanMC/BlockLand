package com.blockland.client.graphics.light;

import com.blockland.BlockLand;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.perf.Optimization;
import com.blockland.client.render.LightSources;
import com.blockland.client.render.SceneLighting;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

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
 * camera-relative, in linear light. An item light may be a spot ({@code cone}): held, it shines along its holder's view
 * from the hand (from the camera for this client's own first-person view); and it may need a switch ({@code switch}: a
 * boolean data component of the stack that must be true, as a flashlight's {@code blockland:lit}). A switched item light
 * is gameplay, not decoration: players' switched-on lights shine whatever the dynamic-light settings say (only the
 * world lighting itself must be on: without it nothing reads the scene's lights).
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
        /** A spot: [inner, outer] half-angles in degrees (null: a point light). Held, it shines along the view. */
        @Nullable
        float[] cone;
        /** A boolean data component of the stack that must be true for it to shine (null: always). JSON key {@code switch}. */
        @Nullable
        @SerializedName("switch")
        String switchComponent;
        transient float r = 1, g = 1, b = 1;
        @Nullable
        transient DataComponentType<?> switchType;
        transient boolean switchMissing;

        boolean spot() {
            return cone != null && cone.length == 2;
        }
    }

    private static final class File {
        Map<String, ItemLight> lights = Map.of();
    }

    private record Candidate(LightSources.Light light, double score) {
    }

    private Map<ResourceLocation, ItemLight> items = Map.of();
    private final List<Candidate> candidates = new ArrayList<>();
    /**
     * The chosen lights, 12 floats each: camera-relative position and radius; linear colour × intensity and a spot's
     * inner cosine; a spot's direction and outer cosine (0, 0, 0, {@link LightSources.Light#POINT} for a point light).
     */
    private final float[] data = new float[SceneLighting.MAX_LIGHTS * 12];
    private int count, spots;

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

    /** How many of this frame's lights are spots. */
    int spots() {
        return spots;
    }

    /**
     * Gathers this frame's lights around the camera and keeps the strongest. The features' lights and every light item
     * follow the dynamic-light settings; players' switched-on item lights (a flashlight) shine regardless.
     */
    void collect(@Nullable ClientLevel level, double camX, double camY, double camZ, float partial, GraphicsSettings settings) {
        candidates.clear();
        count = 0;
        spots = 0;
        if (level == null || !settings.dynamicLights) {
            LightSources.collect(light -> { }); // keeps the flash list from growing while nothing reads it
        } else {
            LightSources.collect(light -> offer(light, camX, camY, camZ));
        }
        if (level == null) return;
        boolean all = settings.dynamicLights && settings.heldItemLights;
        try (FrameTimings.Span ignored = FrameTimings.span("bl/lights/entities")) {
            if (all) {
                entityLights(level, camX, camY, camZ, partial);
            } else {
                // only the players' switched lights: a short list, whatever the settings
                for (Player player : level.players()) {
                    if (player.isRemoved() || player.distanceToSqr(camX, camY, camZ) > ENTITY_RANGE * ENTITY_RANGE) continue;
                    Vec3 at = player.getPosition(partial);
                    double y = at.y + player.getBbHeight() * 0.62;
                    held(player, InteractionHand.MAIN_HAND, at.x, y, at.z, partial, camX, camY, camZ, true);
                    held(player, InteractionHand.OFF_HAND, at.x, y, at.z, partial, camX, camY, camZ, true);
                }
            }
        }
        choose(camX, camY, camZ);
    }

    /** Keeps the {@link SceneLighting#MAX_LIGHTS} strongest candidates, packed as the scene block wants them. */
    void choose(double camX, double camY, double camZ) {
        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        for (Candidate candidate : candidates) {
            if (count == SceneLighting.MAX_LIGHTS) break;
            LightSources.Light l = candidate.light;
            pack(l, camX, camY, camZ, data, count * 12);
            if (l.isSpot()) spots++;
            count++;
        }
    }

    /**
     * One light as {@code BlLights[3i..3i+2]} reads it (12 floats from {@code at}): camera-relative position and radius;
     * linear colour × intensity and a spot's inner cosine; a spot's direction and outer cosine (a point light: 0, 0, 0,
     * {@link LightSources.Light#POINT}).
     */
    static void pack(LightSources.Light l, double camX, double camY, double camZ, float[] out, int at) {
        out[at] = (float) (l.x() - camX);
        out[at + 1] = (float) (l.y() - camY);
        out[at + 2] = (float) (l.z() - camZ);
        out[at + 3] = l.radius();
        out[at + 4] = linear(l.r()) * l.intensity();
        out[at + 5] = linear(l.g()) * l.intensity();
        out[at + 6] = linear(l.b()) * l.intensity();
        out[at + 7] = l.isSpot() ? l.cosInner() : 0;
        out[at + 8] = l.isSpot() ? l.dx() : 0;
        out[at + 9] = l.isSpot() ? l.dy() : 0;
        out[at + 10] = l.isSpot() ? l.dz() : 0;
        out[at + 11] = l.isSpot() ? l.cosOuter() : LightSources.Light.POINT;
    }

    /** BlLights of the scene block (all slots, three vectors each; unused ones zero). */
    void write(Std140Builder out) {
        for (int i = 0; i < SceneLighting.MAX_LIGHTS; i++) {
            if (i < count) {
                int k = i * 12;
                out.putVec4(data[k], data[k + 1], data[k + 2], data[k + 3]);
                out.putVec4(data[k + 4], data[k + 5], data[k + 6], data[k + 7]);
                out.putVec4(data[k + 8], data[k + 9], data[k + 10], data[k + 11]);
            } else {
                out.putVec4(0, 0, 0, 0);
                out.putVec4(0, 0, 0, 0);
                out.putVec4(0, 0, 0, LightSources.Light.POINT);
            }
        }
    }

    /** Light items held by the entities near the camera or lying there, and burning entities. */
    private void entityLights(ClientLevel level, double camX, double camY, double camZ, float partial) {
        boolean garbageFree = Optimization.FRAME_GARBAGE.on();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.distanceToSqr(camX, camY, camZ) > ENTITY_RANGE * ENTITY_RANGE) continue;
            double atX, atY, atZ;
            if (garbageFree) {
                // Entity.getPosition(partial)'s own lerps, without its vector
                atX = Mth.lerp(partial, entity.xo, entity.getX());
                atY = Mth.lerp(partial, entity.yo, entity.getY());
                atZ = Mth.lerp(partial, entity.zo, entity.getZ());
            } else {
                Vec3 at = entity.getPosition(partial);
                atX = at.x;
                atY = at.y;
                atZ = at.z;
            }
            if (entity instanceof LivingEntity living) {
                double y = atY + living.getBbHeight() * 0.62;
                held(living, InteractionHand.MAIN_HAND, atX, y, atZ, partial, camX, camY, camZ, false);
                held(living, InteractionHand.OFF_HAND, atX, y, atZ, partial, camX, camY, camZ, false);
            } else if (entity instanceof ItemEntity item) {
                ItemLight light = lightOf(item.getItem());
                // a spot lying on the ground: a glow round it, not a beam
                if (light != null) offer(new LightSources.Light(atX, atY + 0.25, atZ, light.r, light.g, light.b,
                        light.spot() ? light.intensity * 0.15f : light.intensity, light.spot() ? 3 : light.radius), camX, camY, camZ);
            }
            if (entity.displayFireAnimation()) {
                offer(new LightSources.Light(atX, atY + entity.getBbHeight() * 0.5, atZ, FIRE.r(), FIRE.g(), FIRE.b(),
                        FIRE.intensity(), FIRE.radius()), camX, camY, camZ);
            }
        }
    }

    /**
     * A light item in a hand: a point light at the body ({@code x, y, z}), or a spot from the hand along the view — for
     * this client's own first-person view from just below and beside the camera along its look. {@code switchedOnly}:
     * only lights with a switch (the dynamic-light settings are off).
     */
    private void held(LivingEntity living, InteractionHand hand, double x, double y, double z, float partial, double camX,
                      double camY, double camZ, boolean switchedOnly) {
        ItemLight light = lightOf(living.getItemInHand(hand));
        if (light == null || switchedOnly && light.switchComponent == null) return;
        LightSources.Light base = new LightSources.Light(x, y, z, light.r, light.g, light.b, light.intensity, light.radius);
        if (!light.spot()) {
            offer(base, camX, camY, camZ);
            return;
        }
        boolean right = (hand == InteractionHand.MAIN_HAND) == (living.getMainArm() == HumanoidArm.RIGHT);
        int side = right ? 1 : -1;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        double px, py, pz, dx, dy, dz;
        if (camera.getEntity() == living && !camera.isDetached()) {
            Vector3f look = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
            Vec3 eye = camera.getPosition();
            px = eye.x + look.x() * 0.35 - up.x() * 0.16 - left.x() * 0.14 * side;
            py = eye.y + look.y() * 0.35 - up.y() * 0.16 - left.y() * 0.14 * side;
            pz = eye.z + look.z() * 0.35 - up.z() * 0.16 - left.z() * 0.14 * side;
            dx = look.x();
            dy = look.y();
            dz = look.z();
        } else {
            Vec3 view = living.getViewVector(partial);
            double yaw = Math.toRadians(Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot));
            // the hand held out before the shoulder: beside the body, below the eyes, a little ahead
            double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
            double scale = living.getScale();
            px = x + rightX * 0.36 * side * scale + view.x * 0.55 * scale;
            py = y - living.getBbHeight() * 0.62 + living.getEyeHeight() - 0.38 * scale + view.y * 0.55 * scale;
            pz = z + rightZ * 0.36 * side * scale + view.z * 0.55 * scale;
            dx = view.x;
            dy = view.y;
            dz = view.z;
        }
        LightSources.Light spot = new LightSources.Light(px, py, pz, light.r, light.g, light.b, light.intensity, light.radius)
                .spot(dx, dy, dz, light.cone[0], light.cone[1]);
        offer(spot, camX, camY, camZ);
    }

    /** The stack's light, if it has one and is switched on. */
    @Nullable
    private ItemLight lightOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ItemLight light = items.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (light == null || light.switchComponent == null) return light;
        if (light.switchType == null && !light.switchMissing) {
            ResourceLocation id = ResourceLocation.tryParse(light.switchComponent);
            light.switchType = id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
            light.switchMissing = light.switchType == null;
        }
        return light.switchType != null && Boolean.TRUE.equals(stack.get(light.switchType)) ? light : null;
    }

    void offer(LightSources.Light light, double camX, double camY, double camZ) {
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
