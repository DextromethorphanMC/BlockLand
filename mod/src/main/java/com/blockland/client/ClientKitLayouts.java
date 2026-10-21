package com.blockland.client;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The player's kit arrangements, stored on this computer ({@code config/blockland-kit-layouts.json}).
 * They are sent to the server when queueing; the server only validates them against its preset.
 */
public final class ClientKitLayouts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, Layout>>() { }.getType();

    /** @param signature the server's kit signature the arrangement was made for */
    public record Layout(String signature, List<String> slots) {
    }

    private static Map<String, Layout> layouts;

    private ClientKitLayouts() {
    }

    /** The saved arrangement, if it was made for the kit identified by {@code signature}. */
    public static Optional<Layout> get(String modeId, String signature) {
        return any(modeId).filter(l -> l.signature().equals(signature));
    }

    /** Whatever is saved for the mode (the server decides whether it still fits). */
    public static Optional<Layout> any(String modeId) {
        return Optional.ofNullable(load().get(modeId));
    }

    public static void save(String modeId, String signature, List<String> slots) {
        load().put(modeId, new Layout(signature, List.copyOf(slots)));
        write();
    }

    public static void remove(String modeId) {
        if (load().remove(modeId) != null) write();
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("blockland-kit-layouts.json");
    }

    private static Map<String, Layout> load() {
        if (layouts != null) return layouts;
        layouts = new LinkedHashMap<>();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Layout> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FILE_TYPE);
                if (read != null) layouts.putAll(read);
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Could not read {}", file, e);
            }
        }
        return layouts;
    }

    private static void write() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(layouts, FILE_TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not save {}", file, e);
        }
    }
}
