package com.blockland.graffiti;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every wall graffiti, read once from {@code /data/blockland/graffiti/graffiti.json} on the classpath (the same file on
 * client and server). Players own them as inventory items {@code graffiti:<id>} and put up to four in the slots
 * {@code graffiti.1} … {@code graffiti.4}.
 */
public final class GraffitiCatalog {
    public static final String PREFIX = "graffiti:";
    public static final int SLOTS = 4;
    private static final String RESOURCE = "/data/blockland/graffiti/graffiti.json";
    private static GraffitiCatalog instance;

    private final Map<String, GraffitiData.Graffiti> graffiti;
    private final GraffitiData.Rules rules;

    private static final class File {
        GraffitiData.Rules rules = new GraffitiData.Rules();
        Map<String, GraffitiData.Graffiti> graffiti = new LinkedHashMap<>();
    }

    private GraffitiCatalog(File file) {
        file.graffiti.forEach((id, g) -> g.id = id);
        this.graffiti = Collections.unmodifiableMap(file.graffiti);
        this.rules = file.rules == null ? new GraffitiData.Rules() : file.rules;
        BlockLand.LOGGER.info("[BlockLand] Graffiti catalog: {} graffiti", graffiti.size());
    }

    public static synchronized GraffitiCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static GraffitiCatalog load() {
        try (InputStream in = GraffitiCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, new TypeToken<File>() { }.getType());
                return new GraffitiCatalog(file == null ? new File() : file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the graffiti catalog", e);
            return new GraffitiCatalog(new File());
        }
    }

    public GraffitiData.Rules rules() {
        return rules;
    }

    public Collection<GraffitiData.Graffiti> all() {
        return graffiti.values();
    }

    @Nullable
    public GraffitiData.Graffiti byId(String id) {
        return id == null ? null : graffiti.get(id);
    }

    /** The graffiti of an inventory key ({@code graffiti:<id>}), or null. */
    @Nullable
    public GraffitiData.Graffiti byKey(@Nullable String key) {
        return key != null && key.startsWith(PREFIX) ? graffiti.get(key.substring(PREFIX.length())) : null;
    }

    /** The free graffiti, in file order: what an empty slot falls back to (slot n → the n-th). */
    public List<GraffitiData.Graffiti> free() {
        List<GraffitiData.Graffiti> free = new ArrayList<>();
        for (GraffitiData.Graffiti g : graffiti.values()) if (g.free) free.add(g);
        return free;
    }

    /** The {@code Cosmetics} slot name of slot {@code n} (1-based). */
    public static String slot(int n) {
        return "graffiti." + n;
    }
}
