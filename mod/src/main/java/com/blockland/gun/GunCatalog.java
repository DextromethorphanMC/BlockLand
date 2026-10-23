package com.blockland.gun;

import com.blockland.BlockLand;
import com.blockland.craft.AttachmentCatalog;
import com.blockland.craft.CraftData;
import com.blockland.craft.Craftable;
import com.blockland.craft.SkinLibrary;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.annotation.Nullable;
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
 * All gun definitions, read once from {@code /data/blockland/guns/guns.json} on the classpath (the same file on
 * client and server, so both sides agree on capacities, spread and damage). Items are registered from it, so it
 * is loaded during mod construction, before any world exists.
 */
public final class GunCatalog {
    private static final String RESOURCE = "/data/blockland/guns/guns.json";
    private static GunCatalog instance;

    private final Map<String, GunData.Ammo> ammo;
    private final Map<String, GunData.Magazine> magazines;
    private final Map<String, GunData.Gun> guns;

    /** JSON layout of the file. */
    private static final class File {
        Map<String, GunData.Ammo> ammo = new LinkedHashMap<>();
        Map<String, GunData.Magazine> magazines = new LinkedHashMap<>();
        Map<String, GunData.Gun> guns = new LinkedHashMap<>();
    }

    private GunCatalog(File file) {
        file.ammo.forEach((id, value) -> value.id = id);
        file.magazines.forEach((id, value) -> value.id = id);
        file.guns.forEach((id, gun) -> {
            gun.id = id;
            gun.skins.forEach((skinId, skin) -> skin.id = skinId);
            if (!gun.skins.containsKey("default")) {
                CraftData.Skin standard = new CraftData.Skin();
                standard.id = "default";
                standard.name = "标准";
                gun.skins.put("default", standard);
            }
            SkinLibrary.get().addTo(Craftable.Kind.GUN, id, gun.skins);
            gun.resolveSlots(file.magazines);
        });
        this.ammo = Collections.unmodifiableMap(file.ammo);
        this.magazines = Collections.unmodifiableMap(file.magazines);
        this.guns = Collections.unmodifiableMap(file.guns);
        validate();
    }

    public static synchronized GunCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static GunCatalog load() {
        try (InputStream in = GunCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, new TypeToken<File>() { }.getType());
                return new GunCatalog(file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load gun catalog", e);
            return new GunCatalog(new File());
        }
    }

    /** Reports broken references once at load time instead of failing later in a match. */
    private void validate() {
        guns.values().forEach(gun -> {
            if (!ammo.containsKey(gun.ammo)) BlockLand.LOGGER.warn("[BlockLand] Gun {} uses unknown ammo {}", gun.id, gun.ammo);
            gun.magazines.forEach(id -> {
                GunData.Magazine magazine = magazines.get(id);
                if (magazine == null || !magazine.guns.contains(gun.id) || !magazine.ammo.equals(gun.ammo)) {
                    BlockLand.LOGGER.warn("[BlockLand] Gun {} lists magazine {} that does not fit it", gun.id, id);
                }
            });
            for (Craftable.Slot slot : gun.slots()) {
                if (slot.id().equals(GunData.Gun.MAGAZINE_SLOT)) continue;
                for (String option : slot.options()) {
                    if (!option.equals(CraftData.NONE) && AttachmentCatalog.get().get(option) == null) {
                        BlockLand.LOGGER.warn("[BlockLand] Gun {}: slot {} offers unknown attachment {}", gun.id, slot.id(), option);
                    }
                }
            }
        });
        BlockLand.LOGGER.info("[BlockLand] Gun catalog: {} guns, {} ammo types, {} magazines", guns.size(), ammo.size(), magazines.size());
    }

    // ---- lookups --------------------------------------------------------------------------------

    public Collection<GunData.Gun> guns() {
        return guns.values();
    }

    public Collection<GunData.Ammo> ammoTypes() {
        return ammo.values();
    }

    public Optional<GunData.Gun> gun(String id) {
        return Optional.ofNullable(guns.get(id));
    }

    @Nullable
    public GunData.Ammo ammo(String id) {
        return ammo.get(id);
    }

    @Nullable
    public GunData.Magazine magazine(String id) {
        return magazines.get(id);
    }

    /** Whether {@code magazineId} fits {@code gun}: listed by the gun, made for it, and taking its ammo. */
    public boolean fits(GunData.Gun gun, String magazineId) {
        GunData.Magazine magazine = magazines.get(magazineId);
        return magazine != null && gun.magazines.contains(magazineId) && magazine.guns.contains(gun.id) && magazine.ammo.equals(gun.ammo);
    }
}
