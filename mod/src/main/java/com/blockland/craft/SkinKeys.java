package com.blockland.craft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Skins a player must own (asset spec §30: {@link CraftData.Skin#isFree()} false) are inventory items, one entitlement
 * key each: {@code skin:<weapon>/<skin>} owns the skin on that weapon; a shared skin ({@code craft/skins.json}) is also
 * owned on every weapon it joins through {@code skin:<skin>}. Client and server decide ownership with the same helper;
 * the keys follow the backend's item key pattern ({@code <type>:<id>}, lower case).
 */
public final class SkinKeys {
    public static final String PREFIX = "skin:";

    private SkinKeys() {
    }

    /** The key of a skin on one weapon. */
    public static String key(String weaponId, String skinId) {
        return PREFIX + weaponId + "/" + skinId;
    }

    /** The key that owns a shared skin on every weapon it joins. */
    public static String sharedKey(String skinId) {
        return PREFIX + skinId;
    }

    /** Whether a skin is one of the shared library's (it joins weapons by kind or id). */
    public static boolean isShared(CraftData.Skin skin) {
        return skin.forWeapons != null && !skin.forWeapons.isEmpty();
    }

    /** Whether the player may put {@code skin} on {@code weapon}: it is free, or they own one of its keys. */
    public static boolean owned(Craftable weapon, CraftData.Skin skin, Predicate<String> ownsKey) {
        if (skin.isFree()) return true;
        if (ownsKey.test(key(weapon.id(), skin.id))) return true;
        return isShared(skin) && ownsKey.test(sharedKey(skin.id));
    }

    /** Whether the player may put the skin with this id on {@code weapon} (unknown skins are not theirs). */
    public static boolean owned(Craftable weapon, String skinId, Predicate<String> ownsKey) {
        CraftData.Skin skin = weapon.skins().get(skinId);
        return skin != null && owned(weapon, skin, ownsKey);
    }

    /**
     * Every key a skin that must be owned can be bought or granted under, in catalogue order: per weapon for a weapon's
     * own skins, once for a shared skin (owned everywhere it joins).
     */
    public static List<String> lockableKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Craftable weapon : CraftCatalog.all()) {
            for (CraftData.Skin skin : weapon.skins().values()) {
                if (skin.isFree()) continue;
                keys.add(isShared(skin) ? sharedKey(skin.id) : key(weapon.id(), skin.id));
            }
        }
        return new ArrayList<>(keys);
    }

    /** Every key a lockable skin answers to, also the per-weapon keys of shared skins (to tell what a player owns). */
    public static List<String> allKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Craftable weapon : CraftCatalog.all()) {
            for (CraftData.Skin skin : weapon.skins().values()) {
                if (skin.isFree()) continue;
                keys.add(key(weapon.id(), skin.id));
                if (isShared(skin)) keys.add(sharedKey(skin.id));
            }
        }
        return new ArrayList<>(keys);
    }

    /** The weapon and skin a key names: {@code [weapon, skin]} for a per-weapon key, {@code [null, skin]} for a shared one. */
    public static String[] parse(String key) {
        String rest = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        int slash = rest.indexOf('/');
        return slash < 0 ? new String[]{null, rest} : new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }
}
