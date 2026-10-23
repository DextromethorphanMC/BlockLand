package com.blockland.equipment;

import com.blockland.BlockLand;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.craft.SkinLibrary;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * All equipment definitions, read once from {@code /data/blockland/equipment/equipment.json} on the classpath (the same
 * file on client and server, so both agree on timing and flight). Items are registered from it, so it is loaded during
 * mod construction, before any world exists.
 */
public final class EquipmentCatalog {
    private static final String RESOURCE = "/data/blockland/equipment/equipment.json";
    private static EquipmentCatalog instance;

    private final Map<String, EquipmentData.Equipment> items;

    /** JSON layout of the file. */
    private static final class File {
        Map<String, EquipmentData.Equipment> equipment = new LinkedHashMap<>();
    }

    private EquipmentCatalog(File file) {
        file.equipment.forEach((id, item) -> {
            item.id = id;
            item.skins.forEach((skinId, skin) -> skin.id = skinId);
            if (!item.skins.containsKey("default")) {
                CraftData.Skin standard = new CraftData.Skin();
                standard.id = "default";
                standard.name = "标准";
                item.skins.put("default", standard);
            }
            SkinLibrary.get().addTo(Craftable.Kind.EQUIPMENT, id, item.skins);
            item.resolveSlots();
            if (!item.thrown() && item.injectAt > item.useTime) {
                BlockLand.LOGGER.warn("[BlockLand] Equipment {}: injectAt must come before the end of useTime", id);
            }
        });
        this.items = Collections.unmodifiableMap(file.equipment);
        BlockLand.LOGGER.info("[BlockLand] Equipment catalog: {} items", items.size());
    }

    public static synchronized EquipmentCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static EquipmentCatalog load() {
        // "throw" is a Java keyword: the fields are named throwing / throwClip, the JSON keys stay "throw"
        FieldNamingStrategy names = field -> switch (field.getName()) {
            case "throwing", "throwClip" -> "throw";
            default -> field.getName();
        };
        Gson gson = new GsonBuilder().setFieldNamingStrategy(names).create();
        try (InputStream in = EquipmentCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = gson.fromJson(reader, new TypeToken<File>() { }.getType());
                return new EquipmentCatalog(file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load equipment catalog", e);
            return new EquipmentCatalog(new File());
        }
    }

    public Collection<EquipmentData.Equipment> items() {
        return items.values();
    }

    public Optional<EquipmentData.Equipment> item(String id) {
        return Optional.ofNullable(items.get(id));
    }
}
