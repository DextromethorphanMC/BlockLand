package com.blockland.craft;

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

/**
 * Skins shared by many weapons, read once from {@code /data/blockland/craft/skins.json} on the classpath (the same file
 * on client and server). Each says in {@code for} which weapon kinds or ids it joins; the weapon catalogues add them to
 * every matching weapon when they load, so the craft screen, the item state and the server's build validation treat
 * them exactly like a weapon's own skins. A weapon's own skin with the same id wins.
 */
public final class SkinLibrary {
    private static final String RESOURCE = "/data/blockland/craft/skins.json";
    private static SkinLibrary instance;

    private final Map<String, CraftData.Skin> skins;

    /** JSON layout of the file. */
    private static final class File {
        Map<String, CraftData.Skin> skins = new LinkedHashMap<>();
    }

    private SkinLibrary(File file) {
        Map<String, CraftData.Skin> loaded = file.skins == null ? new LinkedHashMap<>() : file.skins;
        loaded.remove("default");
        loaded.values().removeIf(skin -> skin == null);
        loaded.forEach((id, skin) -> skin.id = id);
        this.skins = Collections.unmodifiableMap(loaded);
        BlockLand.LOGGER.info("[BlockLand] Skin library: {} shared skins", skins.size());
    }

    public static synchronized SkinLibrary get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static SkinLibrary load() {
        try (InputStream in = SkinLibrary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return new SkinLibrary(new File());
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, new TypeToken<File>() { }.getType());
                return new SkinLibrary(file == null ? new File() : file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the skin library", e);
            return new SkinLibrary(new File());
        }
    }

    public Collection<CraftData.Skin> all() {
        return skins.values();
    }

    /** Adds every shared skin meant for this weapon to its skins (its own skins keep their place and win on an id clash). */
    public void addTo(Craftable.Kind kind, String weaponId, Map<String, CraftData.Skin> weaponSkins) {
        for (CraftData.Skin skin : skins.values()) {
            if (skin.isFor(kind, weaponId)) weaponSkins.putIfAbsent(skin.id, skin);
        }
    }
}
