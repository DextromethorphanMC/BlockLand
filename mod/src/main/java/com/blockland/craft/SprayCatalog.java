package com.blockland.craft;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every spray (喷漆), read once from {@code /data/blockland/craft/sprays.json} on the classpath (the same file on client and
 * server), and the limits of a placement. Sprays are decals players put on their weapons (asset spec §29); a build
 * keeps them as {@link Spray} placements, which the server checks here ({@link CraftCatalog#check}).
 */
public final class SprayCatalog {
    private static final String RESOURCE = "/data/blockland/craft/sprays.json";
    private static SprayCatalog instance;

    private final Map<String, Def> sprays;
    private final Limits limits;

    /** One spray as written in the file. */
    public static final class Def {
        public String id;
        public String name = "";
        /** RGBA texture under {@code assets/blockland/} (straight alpha), square. */
        public String texture = "";
        /** "common", "legendary" (UI colour only). */
        public String rarity = "common";
        /** Weapon kinds ({@code gun}, {@code knife}), weapon ids or {@code *} it may go on. */
        @SerializedName("for")
        public List<String> forWeapons = List.of("*");

        public boolean isFor(Craftable.Kind kind, String weaponId) {
            if (forWeapons == null || forWeapons.isEmpty()) return true;
            for (String target : forWeapons) {
                if (target == null) continue;
                if (target.equals("*") || target.equalsIgnoreCase(kind.name()) || target.equals(weaponId)) return true;
            }
            return false;
        }
    }

    /** What a placement may be: how many per weapon and how big (metres). */
    public static final class Limits {
        public int perWeapon = 4;
        public float minSize = 0.012f;
        public float maxSize = 0.09f;
    }

    /** JSON layout of the file. */
    private static final class File {
        Limits limits = new Limits();
        Map<String, Def> sprays = new LinkedHashMap<>();
    }

    private SprayCatalog(File file) {
        Map<String, Def> loaded = file.sprays == null ? new LinkedHashMap<>() : file.sprays;
        loaded.values().removeIf(def -> def == null);
        loaded.forEach((id, def) -> def.id = id);
        this.sprays = Collections.unmodifiableMap(loaded);
        Limits l = file.limits == null ? new Limits() : file.limits;
        l.perWeapon = Math.max(0, Math.min(Build.MAX_SPRAYS, l.perWeapon));
        if (!(l.minSize > 0) || !(l.maxSize >= l.minSize)) l = new Limits();
        this.limits = l;
        BlockLand.LOGGER.info("[BlockLand] Spray catalog: {} sprays, {} per weapon", sprays.size(), limits.perWeapon);
    }

    public static synchronized SprayCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static SprayCatalog load() {
        try (InputStream in = SprayCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return new SprayCatalog(new File());
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, new TypeToken<File>() { }.getType());
                return new SprayCatalog(file == null ? new File() : file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the spray catalog", e);
            return new SprayCatalog(new File());
        }
    }

    public Collection<Def> all() {
        return sprays.values();
    }

    @Nullable
    public Def get(String id) {
        return sprays.get(id);
    }

    public Limits limits() {
        return limits;
    }

    /** Sprays that may go on this weapon, in file order. */
    public List<Def> forWeapon(Craftable weapon) {
        return sprays.values().stream().filter(def -> def.isFor(weapon.kind(), weapon.id())).toList();
    }

    /** Why a placement does not fit this weapon, or null when it does (server validation, asset spec §29). */
    @Nullable
    public String check(Craftable weapon, Spray spray) {
        Def def = sprays.get(spray.id());
        if (def == null) return "unknown spray " + spray.id();
        if (!def.isFor(weapon.kind(), weapon.id())) return "spray " + spray.id() + " does not fit " + weapon.id();
        if (!Spray.LEFT.equals(spray.side()) && !Spray.RIGHT.equals(spray.side())) return "bad spray side";
        if (!within(spray.u(), 0, 1) || !within(spray.v(), 0, 1)) return "spray outside the weapon";
        if (!within(spray.size(), limits.minSize, limits.maxSize)) return "bad spray size " + spray.size();
        if (!within(spray.rotation(), -180, 180)) return "bad spray rotation";
        return null;
    }

    private static boolean within(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }
}
