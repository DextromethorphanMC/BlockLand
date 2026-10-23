package com.blockland.client.settings;

import com.blockland.BlockLand;
import com.blockland.client.camera.CameraRig;
import com.blockland.client.hud.Crosshair;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.scope.ScopeQuality;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's own preferences, kept on this computer ({@code config/blockland-settings.json}): aim-down-sights
 * sensitivity and the look of the crosshair and the hit marker. They only change how this client looks and feels,
 * so the server never sees them; language and look sensitivity are vanilla options, privacy lives on the server.
 */
public final class ClientSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** JSON layout of the file (field names = keys). */
    public static final class Data {
        /** Multiplier of the look sensitivity while aiming down sights. */
        public float adsSensitivity = 1.0f;
        /** Picture in picture for magnified scopes: {@code off} (the whole view zooms), {@code low}, {@code medium}, {@code high}. */
        public String scopeQuality = "medium";
        /** Blur the view around a magnified scope while looking through it (the eye is focused through the scope). */
        public boolean scopeBlurOutside = true;
        /** Darken the view around a scope while looking through it. */
        public boolean scopeDimOutside = false;
        /** Extra multiplier on the sensitivity through a magnified scope (which already slows down with the magnification). */
        public float scopeSensitivity = 1.0f;
        /** Camera feedback (asset spec §32), 0..1.5: impacts (shots, slams, landings, hits) and locomotion sway. */
        public float cameraImpact = 1.0f;
        public float cameraMotion = 1.0f;
        public Crosshair.Style crosshair = new Crosshair.Style();
        public HitMarker.Style hitmarker = new HitMarker.Style();
    }

    private static Data data;

    private ClientSettings() {
    }

    public static Data get() {
        if (data == null) load();
        return data;
    }

    /** Reads the file and hands the styles to the HUD. */
    public static void load() {
        data = new Data();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (read != null) data = read;
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Could not read {}", file, e);
            }
        }
        if (data.crosshair == null) data.crosshair = new Crosshair.Style();
        if (data.hitmarker == null) data.hitmarker = new HitMarker.Style();
        data.adsSensitivity = clamp(data.adsSensitivity, 0.1f, 2f);
        data.scopeQuality = ScopeQuality.of(data.scopeQuality).id();
        data.scopeSensitivity = clamp(data.scopeSensitivity, 0.2f, 2f);
        data.cameraImpact = clamp(data.cameraImpact, 0f, 1.5f);
        data.cameraMotion = clamp(data.cameraMotion, 0f, 1.5f);
        data.crosshair.sanitize();
        data.hitmarker.sanitize();
        apply();
    }

    /** Applies the current values to the HUD and writes the file. */
    public static void save() {
        apply();
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not save {}", file, e);
        }
    }

    private static void apply() {
        Crosshair.setStyle(data.crosshair);
        HitMarker.setStyle(data.hitmarker);
        CameraRig.setIntensity(data.cameraImpact, data.cameraMotion);
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("blockland-settings.json");
    }

    static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
