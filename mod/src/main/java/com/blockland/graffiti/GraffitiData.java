package com.blockland.graffiti;

import java.util.ArrayList;
import java.util.List;

/**
 * Wall graffiti (喷漆) as written in {@code data/blockland/graffiti/graffiti.json} (read by Gson; field names = JSON keys).
 * Normal graffiti are paint on a wall or floor; special ones are more than a picture on a surface — a glowing neon tube,
 * a hologram standing in the air, a piece made of points of light, a sticker in layers, an animation. Not the weapon
 * sprays of 工匠 ({@code craft/Spray*}).
 */
public final class GraffitiData {
    /** Effects of special graffiti (the {@code effect} key). */
    public static final String NEON = "neon", HOLOGRAM = "hologram", PARTICLES = "particles", LAYERED = "layered", ANIMATED = "animated";

    /** How spraying works, for everyone. */
    public static final class Rules {
        /** Blocks from the eyes to the surface sprayed on (or to where a floating piece appears). */
        public float range = 4.5f;
        public float cooldownSeconds = 3;
        /** Pieces of one player in one world at once; the oldest goes when a new one comes. */
        public int maxPerPlayer = 3;
        public int maxPerLevel = 96;
        /** Blocks within which clients draw graffiti. */
        public float renderDistance = 96;
        /** World sound of spraying. */
        public String sound = "blockland:graffiti.spray";
    }

    /** One graffiti. */
    public static final class Graffiti {
        public String id;
        public String name = "";
        /** {@code common}, {@code rare}, {@code epic}, {@code legendary} (colour only). */
        public String rarity = "common";
        /** {@code normal} or {@code special}. */
        public String kind = "normal";
        /** Special graffiti: {@link #NEON}, {@link #HOLOGRAM}, {@link #PARTICLES}, {@link #LAYERED}, {@link #ANIMATED}; empty = plain paint. */
        public String effect = "";
        /** Everyone has it (no entitlement needed). */
        public boolean free;
        /** Texture under {@code assets/blockland/} (a vertical strip of {@link #frames} frames when animated). */
        public String texture = "";
        /** Square preview for menus; empty = the texture (its first frame). */
        public String icon = "";
        /** Layered graffiti: textures from the wall outwards. */
        public List<String> layers = new ArrayList<>();
        /** Width (= height) in blocks. */
        public float size = 1.4f;
        public int frames = 1;
        public float fps = 8;
        /** Neon, hologram, particles: colour {@code #RRGGBB}. */
        public String color = "#FFFFFF";
        /** Light it casts on its surroundings (intensity, 0 = none) and how far. */
        public float light;
        public float lightRadius = 4;
        /** Neon: how much it flickers 0..1. */
        public float flicker;
        /** Particles: number of points and their size (blocks). */
        public int points = 300;
        public float pointSize = 0.035f;
        /** Layered: blocks between the wall and the outermost layer. */
        public float depth = 0.06f;
        /** Floating pieces: blocks between the floor and their lower edge. */
        public float lift = 0.4f;
        /** Stands in the air facing whoever looks at it, instead of lying on a surface. */
        public boolean floating;
        /** Sound it makes where it is (a loop for neon, once for the others); empty = none. */
        public String sound = "";

        public boolean special() {
            return "special".equals(kind) || !effect.isEmpty();
        }

        public String iconTexture() {
            return icon.isEmpty() ? (texture.isEmpty() && !layers.isEmpty() ? layers.get(layers.size() - 1) : texture) : icon;
        }

        /** The key players own it by ({@code graffiti:<id>}). */
        public String key() {
            return GraffitiCatalog.PREFIX + id;
        }

        public int rgb() {
            try {
                return Integer.parseInt(color.startsWith("#") ? color.substring(1) : color, 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                return 0xFFFFFF;
            }
        }
    }
}
