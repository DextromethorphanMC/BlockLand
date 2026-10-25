package com.blockland.games.infection;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The monsters infected players can become, read once from {@code /data/blockland/infection/monsters.json} on the
 * classpath (server side; clients get what they need in the selection packet). Each monster brings its own model
 * (a vanilla entity type), health, movement and skill; left-click damage is the mode's and the same for all.
 */
final class MonsterCatalog {
    private static final String RESOURCE = "/data/blockland/infection/monsters.json";
    private static MonsterCatalog instance;

    private final Map<String, Monster> monsters;

    /** One monster (Gson; field names = JSON keys). */
    static final class Monster {
        String id;
        String name = "";
        String description = "";
        /** Entity type whose model the player is drawn with, e.g. {@code minecraft:zombie}. */
        String entity = "minecraft:zombie";
        float health = 40;
        /** Movement speed multiplier (1 = player walking speed). */
        float speed = 1;
        float knockbackResistance = 0;
        Skill skill = new Skill();
        /** An always-on skill, or null; {@code death_blast}: the monster goes off when it dies (MonsterBlasts). */
        Skill passive;
    }

    /** A monster's active skill; {@code type} picks the implementation in {@link MonsterSkills}. */
    static final class Skill {
        String type = "effect";
        String name = "";
        String description = "";
        /** effect: mob effect id, level (0 = I) and seconds (on the monster itself, or on whoever a projectile hits). */
        String effect = "minecraft:speed";
        int amplifier = 0;
        float duration = 4;
        /** Seconds before the skill can be used again (counted from its start). */
        float cooldown = 15;
        /** Sound event id played for everyone around. */
        String sound = "";
        /** projectile: the entity fired (e.g. {@code blockland:slime_shot}) and its speed in blocks per tick. */
        String projectile = "";
        float speed = 1.5f;
        /**
         * self_destruct / death_blast: seconds of fuse (death_blast: the delay after death) — self_destruct slows the
         * monster by {@code effect} / {@code amplifier} while it burns — and the blast: full {@code damage} within
         * {@code innerRadius} falling off (quadratically) to {@code minDamage} at {@code radius} blocks, shoving
         * everyone it reaches by {@code knockback}.
         */
        float fuse = 1.3f;
        float radius = 4;
        float innerRadius = 1.2f;
        float damage = 20;
        float minDamage = 2;
        float knockback = 0.9f;

        /** Seconds the skill keeps working on the monster itself (for its HUD); projectiles act on others. */
        float activeSeconds() {
            return switch (type) {
                case "effect" -> duration;
                case "self_destruct" -> fuse;
                default -> 0;
            };
        }
    }

    private static final class File {
        Map<String, Monster> monsters = new LinkedHashMap<>();
    }

    private MonsterCatalog(File file) {
        file.monsters.forEach((id, monster) -> monster.id = id);
        this.monsters = Collections.unmodifiableMap(file.monsters);
        BlockLand.LOGGER.info("[BlockLand] Monster catalog: {}", monsters.keySet());
    }

    static synchronized MonsterCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static MonsterCatalog load() {
        try (InputStream in = MonsterCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new MonsterCatalog(new Gson().fromJson(reader, new TypeToken<File>() { }.getType()));
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load monster catalog", e);
            return new MonsterCatalog(new File());
        }
    }

    /** {@code {"monsters": [...]}} as published to the backend (catalog order; ids, names, health and skills). */
    JsonObject registry() {
        JsonArray list = new JsonArray();
        for (Monster m : monsters.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", m.id);
            entry.addProperty("name", m.name);
            entry.addProperty("description", m.description);
            entry.addProperty("entity", m.entity);
            entry.addProperty("health", m.health);
            entry.addProperty("speed", m.speed);
            JsonObject skill = new JsonObject();
            skill.addProperty("name", m.skill.name);
            skill.addProperty("description", m.skill.description);
            skill.addProperty("cooldown", m.skill.cooldown);
            entry.add("skill", skill);
            list.add(entry);
        }
        JsonObject registry = new JsonObject();
        registry.add("monsters", list);
        return registry;
    }

    Optional<Monster> monster(String id) {
        return Optional.ofNullable(monsters.get(id));
    }

    /** The mode's monster list resolved against the catalog (unknown ids dropped; empty = every monster). */
    List<Monster> offered(List<String> ids) {
        List<Monster> offered = new ArrayList<>();
        if (ids.isEmpty()) {
            offered.addAll(monsters.values());
        } else {
            for (String id : ids) monster(id).ifPresent(offered::add);
        }
        if (offered.isEmpty()) offered.addAll(monsters.values());
        return offered;
    }
}
