package com.blockland.melee;

import com.blockland.BlockLand;
import com.google.gson.Gson;
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
 * All knife definitions, read once from {@code /data/blockland/melee/knives.json} on the classpath (the same file on
 * client and server, so both agree on reach and timing). Items are registered from it, so it is loaded during mod
 * construction, before any world exists.
 */
public final class MeleeCatalog {
    private static final String RESOURCE = "/data/blockland/melee/knives.json";
    private static MeleeCatalog instance;

    private final Map<String, MeleeData.Knife> knives;

    /** JSON layout of the file. */
    private static final class File {
        Map<String, MeleeData.Knife> knives = new LinkedHashMap<>();
    }

    private MeleeCatalog(File file) {
        file.knives.forEach((id, knife) -> knife.id = id);
        this.knives = Collections.unmodifiableMap(file.knives);
        knives.values().forEach(knife -> {
            for (MeleeData.Attack attack : new MeleeData.Attack[]{knife.light, knife.heavy}) {
                if (attack.impact > attack.duration || attack.cooldown < attack.impact) {
                    BlockLand.LOGGER.warn("[BlockLand] Knife {}: impact must come before the end of the attack and the cooldown", knife.id);
                }
                if (attack.swings.isEmpty()) BlockLand.LOGGER.warn("[BlockLand] Knife {}: an attack has no swings", knife.id);
            }
        });
        BlockLand.LOGGER.info("[BlockLand] Melee catalog: {} knives", knives.size());
    }

    public static synchronized MeleeCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static MeleeCatalog load() {
        try (InputStream in = MeleeCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, new TypeToken<File>() { }.getType());
                return new MeleeCatalog(file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load melee catalog", e);
            return new MeleeCatalog(new File());
        }
    }

    public Collection<MeleeData.Knife> knives() {
        return knives.values();
    }

    public Optional<MeleeData.Knife> knife(String id) {
        return Optional.ofNullable(knives.get(id));
    }
}
