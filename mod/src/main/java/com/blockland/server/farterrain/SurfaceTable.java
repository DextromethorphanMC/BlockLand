package com.blockland.server.farterrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What an estimated column looks like on top, per biome ({@code data/blockland/farterrain/surfaces.json}, rendering spec
 * §22): the world generator gives the terrain's shape and the sea, this table the blocks a viewer sees — the surface and
 * the block under it, the floor under water (or ice on it), stone above a stone line, snow above a snow line, and a canopy
 * of trees covering a share of the columns. A biome takes its own entry, else the first matching {@code #tag} entry in
 * the file's order, else {@code default}; fields an entry leaves out come from {@code default}.
 */
final class SurfaceTable {
    /** Trees: how far the canopy stands above the ground, its leaves, the share of columns it covers. */
    record Canopy(int height, String leaves, float cover) {
    }

    /** One biome's look; block ids, null fields inherit (in a merged rule only the optional ones are null). */
    record Rule(@Nullable String surface, @Nullable String under, @Nullable String floor, @Nullable String waterTop,
                @Nullable Integer snowLine, @Nullable String snow, @Nullable Integer stoneLine, @Nullable String stone,
                @Nullable Canopy canopy) {
        Rule over(Rule base) {
            return new Rule(or(surface, base.surface), or(under, base.under), or(floor, base.floor), or(waterTop, base.waterTop),
                    or(snowLine, base.snowLine), or(snow, base.snow), or(stoneLine, base.stoneLine), or(stone, base.stone),
                    or(canopy, base.canopy));
        }

        private static <T> T or(@Nullable T a, @Nullable T b) {
            return a != null ? a : b;
        }
    }

    static final Rule FALLBACK = new Rule("minecraft:grass_block", "minecraft:dirt", "minecraft:sand", null, null,
            "minecraft:snow_block", null, "minecraft:stone", null);

    private final Rule fallback;
    private final Map<String, Rule> biomes = new LinkedHashMap<>();
    private final List<Map.Entry<String, Rule>> tags = new ArrayList<>();

    private SurfaceTable(Rule fallback) {
        this.fallback = fallback;
    }

    static SurfaceTable parse(@Nullable JsonObject root) {
        if (root == null) return new SurfaceTable(FALLBACK);
        Rule base = root.has("default") && root.get("default").isJsonObject() ? rule(root.getAsJsonObject("default")).over(FALLBACK) : FALLBACK;
        SurfaceTable table = new SurfaceTable(base);
        if (root.has("biomes") && root.get("biomes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("biomes").entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                Rule rule = rule(e.getValue().getAsJsonObject()).over(base);
                String key = e.getKey().trim();
                if (key.startsWith("#")) table.tags.add(Map.entry(key.substring(1), rule));
                else table.biomes.put(key, rule);
            }
        }
        return table;
    }

    /** The merged rule of a biome; {@code hasTag} answers whether the biome carries a tag (resource id without '#'). */
    Rule rule(String biome, Predicate<String> hasTag) {
        Rule rule = biomes.get(biome);
        if (rule != null) return rule;
        for (Map.Entry<String, Rule> tag : tags) {
            if (hasTag.test(tag.getKey())) return tag.getValue();
        }
        return fallback;
    }

    Rule fallback() {
        return fallback;
    }

    private static Rule rule(JsonObject o) {
        Canopy canopy = null;
        if (o.has("canopy") && o.get("canopy").isJsonObject()) {
            JsonObject c = o.getAsJsonObject("canopy");
            int height = c.has("height") ? c.get("height").getAsInt() : 5;
            float cover = c.has("cover") ? c.get("cover").getAsFloat() : 0.7f;
            String leaves = c.has("leaves") ? c.get("leaves").getAsString() : "minecraft:oak_leaves";
            if (height > 0 && cover > 0) canopy = new Canopy(Math.min(height, 64), leaves, Math.min(1, cover));
        }
        return new Rule(string(o, "surface"), string(o, "under"), string(o, "floor"), string(o, "waterTop"), integer(o, "snowLine"),
                string(o, "snow"), integer(o, "stoneLine"), string(o, "stone"), canopy);
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    @Nullable
    private static Integer integer(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : null;
    }
}
