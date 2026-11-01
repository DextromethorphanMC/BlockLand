package com.blockland.client.hud.damage;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.Optional;

/**
 * The damage arrows' look and timing ({@code assets/blockland/hud/damage_indicator.json}, UI spec §3.5), read by Gson.
 * Lengths are in ring radii (the ring the arrows lie on has radius 1), angles in degrees, times in seconds.
 */
final class DamageIndicatorData {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation FILE = BlockLand.id("hud/damage_indicator.json");

    /** How steeply the virtual camera looks down at the ring (from level; 90 = straight down, a flat ring). */
    float cameraTilt = 58;
    /** How far the virtual camera is from the ring's middle, and its vertical field of view. */
    float cameraDistance = 4.0f, fov = 38;
    /** The ring of an arrow's span at its weakest and at its strongest. */
    float[] arc = {26, 44};
    /** The band's thickness at full strength; the arrowhead's length and half width. */
    float thickness = 0.12f, headLength = 0.2f, headWidth = 0.14f;
    /** The soft edge round the band; how far its glow spreads (in thicknesses) and how bright it is. */
    float feather = 0.022f, glowSpread = 1.7f, glowAlpha = 0.3f;
    /** The most the attacker's height tips an arrow up or down, and how far it lifts it at ±90°. */
    float maxTilt = 30, lift = 0.12f;
    /** However it is tipped, an arrow's face is seen at least this far from edge-on (degrees). */
    float minFacing = 40;
    /** An arrow stays this long after its latest hit, then fades out over {@code fade}. */
    float hold = 1.15f, fade = 0.7f;
    /** A hit's pop: how much bigger the arrow starts (for a strong hit), how fast it settles (1/s), its wobble (rad/s). */
    float popScale = 0.55f, popDamping = 13, popFrequency = 24;
    /** A hit's inward punch: how far towards the middle at most, and when it is deepest. */
    float punch = 0.16f, punchTime = 0.05f;
    /** How long the white-hot flash of a hit takes to cool down to the arrow's colour. */
    float flash = 0.24f;
    /** Recent damage (health points) that makes an arrow its strongest, how fast it halves, the weakest arrow. */
    float fullDamage = 8, halfLife = 0.8f, minStrength = 0.5f;
    /** Hits with no attacker to follow this close (blocks) to an arrow's point join that arrow; at most this many. */
    float mergeDistance = 3;
    int maxArrows = 8;
    /** Colours (#RRGGBB): a hurt that took health, one the armour's plates soaked, a fresh hit's flash. */
    String health = "#FF2A1F", plates = "#4FC3FF", hot = "#FFF6E8";

    transient int healthColor, platesColor, hotColor;

    static DamageIndicatorData load(ResourceManager manager) {
        DamageIndicatorData data = new DamageIndicatorData();
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isPresent()) {
            try (Reader reader = resource.get().openAsReader()) {
                DamageIndicatorData read = GSON.fromJson(reader, DamageIndicatorData.class);
                if (read != null) data = read;
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Could not read {}", FILE, e);
            }
        }
        data.resolve();
        return data;
    }

    DamageIndicatorData resolve() {
        if (arc == null || arc.length < 2) arc = new float[]{26, 44};
        healthColor = color(health, 0xFFFF2A1F);
        platesColor = color(plates, 0xFF4FC3FF);
        hotColor = color(hot, 0xFFFFF6E8);
        return this;
    }

    private static int color(String hex, int fallback) {
        try {
            return 0xFF000000 | Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
