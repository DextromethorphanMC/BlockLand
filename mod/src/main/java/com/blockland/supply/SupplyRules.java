package com.blockland.supply;

import com.blockland.BlockLand;
import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Ammo crate tuning as written in {@code data/blockland/supply/ammo_crate.json} (read by Gson; field names = JSON keys),
 * read once from the classpath — the same file on client and server.
 */
public final class SupplyRules {
    private static final String RESOURCE = "/data/blockland/supply/ammo_crate.json";
    private static SupplyRules instance;

    /**
     * A use tops the held gun's ammunition up to this many spare magazines (of the fitted magazine's capacity) on top of
     * a full gun: the rounds go into the inventory, and the reload that follows the resupply fills the gun from them.
     */
    public int magazines = 3;
    /** Seconds between two uses by the same player. */
    public float cooldown = 1.5f;
    /** Blocks from the eye to the crate's centre a use may be made from. */
    public float reach = 4.5f;
    /** Seconds the lid stays open after a use. */
    public float openSeconds = 2.4f;

    private SupplyRules() {
    }

    public static synchronized SupplyRules get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static SupplyRules load() {
        try (InputStream in = SupplyRules.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                SupplyRules rules = new Gson().fromJson(reader, SupplyRules.class);
                return rules == null ? new SupplyRules() : rules;
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the ammo crate rules", e);
            return new SupplyRules();
        }
    }

    public int openTicks() {
        return Math.max(10, Math.round(openSeconds * 20));
    }
}
