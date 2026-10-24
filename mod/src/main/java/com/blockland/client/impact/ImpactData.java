package com.blockland.client.impact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What bullet impacts look like, as written in {@code assets/blockland/impacts.json} (read by Gson; field names = JSON
 * keys; rendering spec §12). Materials say what a hit throws out (debris chunks, dust, sparks, droplets, a splash); rules
 * map blocks, entities and fluids to materials; ammo profiles scale everything by the round; the environment block says
 * how rain, snow, water and wind change it. Speeds are blocks per second, sizes blocks, times seconds.
 */
final class ImpactData {
    /** Debris shapes the renderer knows (rendering spec §12). */
    static final String CHIP = "chip", FLAKE = "flake", SPLINTER = "splinter", SHARD = "shard", GRAIN = "grain",
            DROPLET = "droplet", LEAF = "leaf";

    Map<String, Material> materials = new LinkedHashMap<>();
    /** Block rules, first match wins: {@code id:<block>}, {@code tag:<block tag>}, {@code sound:<sound type>}. */
    List<Rule> blocks = new ArrayList<>();
    /** Entity rules, first match wins: {@code id:<entity type>}, {@code vehicle}, {@code armored}, {@code living}, {@code any}. */
    List<Rule> entities = new ArrayList<>();
    /** Fluid rules: {@code tag:<fluid tag>} or {@code id:<fluid>}. */
    List<Rule> fluids = new ArrayList<>();
    String fallbackBlock = "stone";
    String fallbackEntity = "flesh";
    Map<String, Ammo> ammo = new LinkedHashMap<>();
    String fallbackAmmo = "rifle";
    Environment environment = new Environment();

    static final class Rule {
        String match = "";
        String material = "";
    }

    /** What one kind of surface throws out when a round strikes it. */
    static final class Material {
        Debris debris = new Debris();
        Dust dust = new Dust();
        Sparks sparks = new Sparks();
        Splash splash;
        /** Debris and droplets take their look from the struck block's own texture (false: {@code sprite} tinted by {@code color}). */
        boolean blockTexture = true;
        /** Block atlas sprite for untextured debris (droplets, blood), e.g. {@code minecraft:block/white_concrete}. */
        String sprite = "minecraft:block/white_concrete";
        /** #RRGGBB tint of untextured debris and, unless the block gives one, the dust. */
        String color = "#8a8a8a";
        /** Dust colour comes from the block's map colour (true) or {@link #color}. */
        boolean blockDustColor = true;
        /** Sound: {@code block} (the struck block's hit sound), a sound id, or empty. */
        String sound = "block";
        float soundVolume = 0.35f;
        /** A point light at the hit for this many seconds (sparks in the dark), 0 = none; colour #RRGGBB. */
        float flash = 0;
        String flashColor = "#FFC070";
    }

    /** Chunks thrown out on ballistic paths, bouncing off what they hit. */
    static final class Debris {
        float count = 0;
        List<String> shapes = List.of(CHIP);
        /** Half-size range. */
        float[] size = {0.02f, 0.05f};
        float[] speed = {2, 5};
        /** Half-angle of the cone the chunks leave in (degrees). */
        float spread = 35;
        float[] life = {1.2f, 2.4f};
        /** Gravity multiplier (1 = 0.04 blocks/tick², like vanilla items). */
        float gravity = 1;
        /** Velocity lost per tick in the air (flakes and leaves drift, chips fly). */
        float drag = 0.02f;
        /** Speed kept on a bounce against the surface, and along it. */
        float restitution = 0.3f;
        float friction = 0.55f;
        /** Degrees per second the chunks tumble at most. */
        float spin = 720;
        /** 0..1 how much the wind carries them. */
        float wind = 0.05f;
    }

    /**
     * The powder a hit pulverises: one cloud per hit whose mass — and with it its size, density and how long it hangs —
     * comes from the material thrown out: {@code (count + perDebris × chunks) × friability × the round's dust}. A few
     * chips of wood leave a faint wisp; a rifle round into concrete leaves a thick cloud with a dense core.
     */
    static final class Dust {
        /** Mass of powder independent of the chunks (a puff even when nothing solid flies). */
        float count = 0;
        /** Mass per chunk thrown out (before quality scaling: the cloud does not thin with fewer drawn chunks). */
        float perDebris = 0;
        /** 0..1 how readily the material turns to powder (sand, plaster, concrete high; metal, glass low). */
        float friability = 0.6f;
        /** Cloud radius (blocks) for a mass of 1: just after the burst and at the end of its life (× ∛mass). */
        float[] size = {0.16f, 0.55f};
        /** Speed the cloud's centre is thrown out at (blocks per second), along the spray. */
        float[] speed = {0.6f, 1.6f};
        float[] life = {1.4f, 2.8f};
        /** Upward drift and sinking of the heavy part (blocks per second), how strongly the wind carries it (0..1). */
        float rise = 0.15f;
        float settle = 0.1f;
        float wind = 0.6f;
        /** Optical density scale: the core's thickness at the burst (≈ optical depth through the middle per block). */
        float opacity = 0.5f;
        /** 0..1 share of the chunks that trail a streak of dust in flight. */
        float trail = 0;
        /** Mass of the small poof a chunk raises where it lands (0 = none). */
        float landing = 0;
    }

    /** Hot streaks (metal on metal, stone struck hard) and embers. */
    static final class Sparks {
        float count = 0;
        float[] speed = {4, 9};
        float[] life = {0.15f, 0.45f};
        /** Sparks keep to the ricochet direction (1) or burst out (0). */
        float ricochet = 0.6f;
        float spread = 50;
        float gravity = 0.6f;
        /** Sparks bounce once off what they hit. */
        boolean bounce = true;
        String color = "#FFB050";
    }

    /**
     * A round going into a fluid (rendering spec §12, fluids): {@code water} — a crown sheet rising round the hole, a
     * column (Worthington jet) after it, drops thrown from both that fall back and splash again, rings spreading on the
     * surface and white water for heavy rounds, all shaded like the water itself; {@code lava} — molten spatter that glows,
     * cools and crusts where it lands, a glowing crater and smoke.
     */
    static final class Splash {
        /** {@code water} or {@code lava}. */
        String kind = "water";
        /** Height of the crown sheet (blocks for energy 1) and of the column. */
        float sheet = 0.24f;
        float column = 0.5f;
        /** Drops thrown out (count for energy 1) and their speed (blocks per second). */
        float drops = 14;
        float dropSpeed = 3.2f;
        /** Spreading speed of the surface rings (blocks per second) and how long they last (seconds). */
        float ripple = 0.95f;
        float rippleLife = 1.6f;
        /** 0..1 white water for heavy rounds (energy above 1). */
        float foam = 0.6f;
    }

    /** How a round scales the effect (pistol 1). */
    static final class Ammo {
        /** Speed of what is thrown out. */
        float energy = 1;
        /** Number of chunks, droplets and sparks. */
        float count = 1;
        float size = 1;
        /** Volume of the dust (a heavy round pulverises more). */
        float dust = 1;
    }

    /** What the weather and the surroundings change. */
    static final class Environment {
        /** A wet surface (rain on it): dust × this, debris colour × {@link #wetDarken}, plus a little muddy splash. */
        float wetDust = 0.3f;
        float wetDarken = 0.7f;
        float wetSplash = 4;
        /** Snowy biomes: extra white powder. */
        float snowDust = 1.5f;
        /** Under water: debris drag, rising bubbles instead of dust. */
        float waterDrag = 0.18f;
        float bubbles = 6;
        /** Wind in blocks per second, and its multiplier in rain and thunder. */
        float wind = 0.6f;
        float rainWind = 1.8f;
        float thunderWind = 2.6f;
    }
}
