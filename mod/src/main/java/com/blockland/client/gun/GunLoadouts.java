package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.gun.GunCatalog;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.GunPackets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The player's gunsmith builds per gun, stored on this computer ({@code config/blockland-gun-loadouts.json}) like kit
 * layouts. Guns handed out by a match start in their default build; the saved build is applied automatically
 * (the server validates every attachment against guns.json).
 */
public final class GunLoadouts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, Loadout>>() { }.getType();
    private static final long RETRY_NANOS = 3_000_000_000L;

    public record Loadout(String skin, String magazine, String sight, String laser) {
        static Loadout of(GunState state) {
            return new Loadout(state.skin(), state.magazine(), state.sight(), state.laser());
        }

        boolean matches(GunState state) {
            return skin.equals(state.skin()) && magazine.equals(state.magazine()) && sight.equals(state.sight()) && laser.equals(state.laser());
        }

        boolean validFor(GunData.Gun gun) {
            return gun.skins.containsKey(skin) && GunCatalog.get().fits(gun, magazine) && gun.sights.contains(sight) && gun.lasers.contains(laser);
        }
    }

    private static Map<String, Loadout> loadouts;
    /** slot → when we last asked the server to apply a build there (avoids spamming a rejected request). */
    private static final Map<Integer, Long> requested = new HashMap<>();

    private GunLoadouts() {
    }

    public static Optional<Loadout> get(String gunId) {
        return Optional.ofNullable(load().get(gunId));
    }

    /** Saves the build and applies it to the gun in {@code slot} right away. */
    public static void apply(int slot, String gunId, Loadout loadout) {
        load().put(gunId, loadout);
        write();
        send(slot, loadout);
    }

    /** Called every client tick: guns in the inventory that do not match the saved build get it applied. */
    static void applySaved(LocalPlayer player) {
        if (load().isEmpty()) return;
        long now = System.nanoTime();
        var items = player.getInventory().getNonEquipmentItems();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (!(stack.getItem() instanceof GunItem gun)) continue;
            Loadout saved = loadouts.get(gun.gunId());
            GunState state = GunContent.stateOf(stack);
            GunData.Gun def = GunCatalog.get().gun(gun.gunId()).orElse(null);
            if (saved == null || state == null || def == null || saved.matches(state) || !saved.validFor(def)) continue;
            Long last = requested.get(slot);
            if (last != null && now - last < RETRY_NANOS) continue;
            send(slot, saved);
        }
    }

    private static void send(int slot, Loadout loadout) {
        requested.put(slot, System.nanoTime());
        BLNetwork.sendToServer(new GunPackets.C2SGunsmith(slot, loadout.skin(), loadout.magazine(), loadout.sight(), loadout.laser()));
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("blockland-gun-loadouts.json");
    }

    private static Map<String, Loadout> load() {
        if (loadouts != null) return loadouts;
        loadouts = new LinkedHashMap<>();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Loadout> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FILE_TYPE);
                if (read != null) loadouts.putAll(read);
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Could not read {}", file, e);
            }
        }
        return loadouts;
    }

    private static void write() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(loadouts, FILE_TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not save {}", file, e);
        }
    }
}
