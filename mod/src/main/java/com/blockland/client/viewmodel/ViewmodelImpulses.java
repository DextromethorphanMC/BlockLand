package com.blockland.client.viewmodel;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
 * How hard the hands working a weapon shove it (asset spec §31.4): {@code assets/blockland/viewmodel/impulses.json}
 * maps a clip event name (the same names that play sounds: {@code mag_in}, {@code slide_forward}, {@code pump_back},
 * {@code catch} …) to a kick of the whole first-person rig — the weapon, both arms and the camera move together, so the
 * hand that only holds the weapon is jolted by the other hand's slap too. Resource packs can override it; reloaded
 * with F3+T. The motion layer scales it by the weapon's weight.
 */
public final class ViewmodelImpulses {
    private static final ResourceLocation FILE = BlockLand.id("viewmodel/impulses.json");
    @Nullable
    private static Map<String, Impulse> impulses;

    /**
     * One event's kick (Gson; field names = JSON keys), the first peak of each spring at weight 1: millimetres back
     * (towards the eye) / up / right, degrees pitch (muzzle up) / yaw (muzzle left) / roll (top leaning left) about the
     * weapon's pivot, the camera's pitch (looking up) / yaw / roll in degrees, and {@code shake}: low-frequency camera
     * trauma (the weight of a heavy slam, 0..1).
     */
    public static final class Impulse {
        public float back, up, side, pitch, yaw, roll, shake;
        public float[] camera = new float[3];
    }

    private ViewmodelImpulses() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> impulses = null));
    }

    /** The kick of a clip event, null when the event shoves nothing. */
    @Nullable
    public static Impulse get(String event) {
        if (impulses == null) impulses = load();
        return impulses.get(event);
    }

    private static Map<String, Impulse> load() {
        Map<String, Impulse> loaded = new HashMap<>();
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            BlockLand.LOGGER.warn("[BlockLand] Missing {}", FILE);
            return loaded;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            Map<String, Impulse> file = new Gson().fromJson(reader, new TypeToken<Map<String, Impulse>>() { }.getType());
            if (file != null) file.forEach((name, impulse) -> {
                if (impulse.camera == null || impulse.camera.length < 3) impulse.camera = new float[3];
                loaded.put(name, impulse);
            });
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot read {}", FILE, e);
        }
        return loaded;
    }
}
