package com.blockland.client;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The player's kit arrangements, stored on this computer ({@code config/blockland-kit-layouts.json}): per mode a list of
 * named presets and the one in use. Only the preset in use is sent to the server when queueing; the server validates it
 * against its kit. A preset without slots means the server's default arrangement. Files of the older one-layout-per-mode
 * format are read as a single preset.
 */
public final class ClientKitLayouts {
    public static final int MAX_PRESETS = 8;
    public static final int MAX_NAME = 16;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type OLD_FORMAT = new TypeToken<LinkedHashMap<String, Layout>>() { }.getType();
    private static final int VERSION = 2;

    /** @param signature the server's kit signature the arrangement was made for */
    public record Layout(String signature, List<String> slots) {
    }

    /** One named arrangement; empty {@code slots} = the server's default. */
    public record Preset(String name, String signature, List<String> slots) {
        public Preset {
            name = name == null ? "" : name;
            signature = signature == null ? "" : signature;
            slots = slots == null ? List.of() : List.copyOf(slots);
        }

        public boolean isDefault() {
            return slots.isEmpty();
        }
    }

    /** JSON layout of one mode. */
    private static final class ModeLayouts {
        int active;
        List<Preset> presets = new ArrayList<>();
    }

    /** JSON layout of the file. */
    private static final class File {
        int version = VERSION;
        Map<String, ModeLayouts> modes = new LinkedHashMap<>();
    }

    private static File data;

    private ClientKitLayouts() {
    }

    /** The preset in use, if it has an arrangement of its own (the server decides whether it still fits). */
    public static Optional<Layout> any(String modeId) {
        Preset preset = presets(modeId).get(active(modeId));
        return preset.isDefault() ? Optional.empty() : Optional.of(new Layout(preset.signature(), preset.slots()));
    }

    /** The mode's presets (at least one). */
    public static List<Preset> presets(String modeId) {
        ModeLayouts mode = load().modes.get(modeId);
        return mode == null || mode.presets.isEmpty() ? List.of(new Preset(defaultName(1), "", List.of())) : List.copyOf(mode.presets);
    }

    public static int active(String modeId) {
        ModeLayouts mode = load().modes.get(modeId);
        return mode == null ? 0 : Math.max(0, Math.min(mode.presets.size() - 1, mode.active));
    }

    public static void setActive(String modeId, int index) {
        ModeLayouts mode = mode(modeId);
        if (index < 0 || index >= mode.presets.size() || mode.active == index) return;
        mode.active = index;
        write();
    }

    /** Stores an arrangement in preset {@code index} (empty {@code slots} = back to the default). */
    public static void save(String modeId, int index, String signature, List<String> slots) {
        ModeLayouts mode = mode(modeId);
        if (index < 0 || index >= mode.presets.size()) return;
        mode.presets.set(index, new Preset(mode.presets.get(index).name(), slots.isEmpty() ? "" : signature, slots));
        write();
    }

    /** Adds a preset after the others and puts it in use; -1 when the mode already has {@link #MAX_PRESETS}. */
    public static int add(String modeId, String name, String signature, List<String> slots) {
        ModeLayouts mode = mode(modeId);
        if (mode.presets.size() >= MAX_PRESETS) return -1;
        mode.presets.add(new Preset(clean(name, mode.presets.size() + 1), slots.isEmpty() ? "" : signature, slots));
        mode.active = mode.presets.size() - 1;
        write();
        return mode.active;
    }

    public static void rename(String modeId, int index, String name) {
        ModeLayouts mode = mode(modeId);
        if (index < 0 || index >= mode.presets.size()) return;
        Preset preset = mode.presets.get(index);
        mode.presets.set(index, new Preset(clean(name, index + 1), preset.signature(), preset.slots()));
        write();
    }

    /** Deletes a preset; the last one is never deleted, only reset to the default. */
    public static void remove(String modeId, int index) {
        ModeLayouts mode = mode(modeId);
        if (index < 0 || index >= mode.presets.size()) return;
        if (mode.presets.size() == 1) {
            mode.presets.set(0, new Preset(mode.presets.get(0).name(), "", List.of()));
        } else {
            mode.presets.remove(index);
            if (mode.active > index || mode.active >= mode.presets.size()) mode.active = Math.max(0, mode.active - 1);
        }
        write();
    }

    /** "预设 N": the name of a fresh preset. */
    public static String defaultName(int number) {
        return I18n.get("blockland.kit.preset.default_name", number);
    }

    private static String clean(String name, int number) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) return defaultName(number);
        return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
    }

    /** The stored entry of a mode, created with one default preset when it has none. */
    private static ModeLayouts mode(String modeId) {
        ModeLayouts mode = load().modes.computeIfAbsent(modeId, id -> new ModeLayouts());
        if (mode.presets.isEmpty()) mode.presets.add(new Preset(defaultName(1), "", List.of()));
        return mode;
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("blockland-kit-layouts.json");
    }

    private static File load() {
        if (data != null) return data;
        data = new File();
        Path file = file();
        if (!Files.isRegularFile(file)) return data;
        try {
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (json.isJsonObject() && json.getAsJsonObject().has("modes")) {
                File read = GSON.fromJson(json, File.class);
                if (read != null && read.modes != null) data.modes.putAll(read.modes);
                data.modes.values().removeIf(m -> m == null || m.presets == null);
            } else if (json.isJsonObject()) {
                migrate(json.getAsJsonObject());
            }
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not read {}", file, e);
        }
        return data;
    }

    /** The older format, one arrangement per mode: each becomes the mode's only preset. */
    private static void migrate(JsonObject json) {
        Map<String, Layout> old = GSON.fromJson(json, OLD_FORMAT);
        if (old == null) return;
        old.forEach((modeId, layout) -> {
            if (layout == null) return;
            ModeLayouts mode = new ModeLayouts();
            mode.presets.add(new Preset(defaultName(1), layout.signature(), layout.slots()));
            data.modes.put(modeId, mode);
        });
    }

    private static void write() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Could not save {}", file, e);
        }
    }
}
