package com.blockland.armor;

import com.blockland.BlockLand;
import com.blockland.common.Handling;
import com.google.gson.Gson;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body armour tuning as written in {@code data/blockland/armor/armor.json} (read by Gson; field names = JSON keys), read
 * once from the classpath — the same file on client and server — and the plates' arithmetic (asset spec §50).
 */
public final class ArmorRules {
    private static final String RESOURCE = "/data/blockland/armor/armor.json";
    private static ArmorRules instance;

    /** Plates a carrier holds. */
    public int maxPlates = 3;
    /** Damage points one fresh plate soaks before it breaks. */
    public float plateDurability = 10;
    /** A plate with this share of its durability left or less is swapped for the next one put in. */
    public float replaceBelow = 0.5f;
    /** Share of what gets past the plates the carrier itself stops (also with no plate in it). */
    public float vestProtection = 0.1f;
    /** A headshot ignores the armour. */
    public boolean headshotsBypass = false;
    /** Seconds a plate takes to go in, and how far the view dips meanwhile (degrees). */
    public float insertSeconds = 1.1f;
    public float insertDip = 14;
    /** First-person feel of a plate in hand (weight, sway, sprint-out time…). */
    public Handling handling = new Handling();
    /** Damage type id (or {@code #tag}) → share of such a hit the armour takes (0..1); unlisted kinds pass it by. */
    public Map<String, Float> covers = new LinkedHashMap<>();

    private ArmorRules() {
    }

    public static synchronized ArmorRules get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static ArmorRules load() {
        try (InputStream in = ArmorRules.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                ArmorRules rules = new Gson().fromJson(reader, ArmorRules.class);
                return rules == null ? new ArmorRules() : rules;
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the armour rules", e);
            return new ArmorRules();
        }
    }

    public int insertTicks() {
        return Math.max(1, Math.round(insertSeconds * 20));
    }

    /** {@link #placeFor}: the new plate goes into a free slot, or there is no room for it. */
    public static final int ADD = -1, NO_ROOM = -2;

    /**
     * Where a new plate goes: a plate worn down to {@link #replaceBelow} of its durability or less is swapped out (the most
     * worn one; broken plates have fallen out already, leaving a free slot), else into a free slot.
     *
     * @return the index of the plate to swap out, {@link #ADD} or {@link #NO_ROOM}
     */
    public int placeFor(VestState state) {
        int worn = state.mostWorn(plateDurability * replaceBelow);
        if (worn >= 0) return worn;
        return state.count() < maxPlates ? ADD : NO_ROOM;
    }

    /** The carrier with a fresh plate put in where {@link #placeFor} says (unchanged when there is no room). */
    public VestState withPlate(VestState state) {
        int at = placeFor(state);
        return at >= 0 ? state.replaced(at, plateDurability) : at == ADD ? state.inserted(plateDurability) : state;
    }

    /** The share of this hit the armour takes (the largest of the entries it matches), 0 when it is not covered. */
    public float cover(DamageSource source) {
        String type = source.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("");
        float share = 0;
        for (Map.Entry<String, Float> e : covers.entrySet()) {
            String key = e.getKey();
            boolean matches;
            if (key.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(key.substring(1));
                matches = tag != null && source.is(TagKey.create(Registries.DAMAGE_TYPE, tag));
            } else {
                matches = key.equals(type);
            }
            if (matches && e.getValue() != null) share = Math.max(share, Math.min(1, e.getValue()));
        }
        return share;
    }

    /** What a hit did to the armour: its new state, the damage that gets through, what the plates soaked, plates broken. */
    public record Absorbed(VestState state, float passed, float soaked, int broken) {
    }

    /**
     * {@code covered} points of damage meet the carrier: the plates soak them, the outermost first, a plate breaking when
     * its durability runs out; the carrier cuts what gets past them by {@link #vestProtection}.
     */
    public Absorbed absorb(VestState state, float covered) {
        List<Float> plates = new ArrayList<>(state.plates());
        float left = Math.max(0, covered);
        int broken = 0;
        while (left > 0 && !plates.isEmpty()) {
            int outer = plates.size() - 1;
            float durability = plates.get(outer);
            if (durability > left) {
                plates.set(outer, durability - left);
                left = 0;
            } else {
                left -= durability;
                plates.remove(outer);
                broken++;
            }
        }
        return new Absorbed(new VestState(plates), left * (1 - Math.max(0, Math.min(1, vestProtection))), covered - left, broken);
    }
}
