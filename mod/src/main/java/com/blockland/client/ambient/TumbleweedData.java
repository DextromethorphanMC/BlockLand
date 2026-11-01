package com.blockland.client.ambient;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The tumbleweeds' tunables ({@code assets/blockland/ambient/tumbleweeds.json}, rendering spec §29), read by Gson. Distances
 * blocks, speeds blocks/s, times ticks; the block lists take block ids and {@code #tags}.
 */
final class TumbleweedData {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation FILE = BlockLand.id("ambient/tumbleweeds.json");

    int maxCount = 10, matchCount = 8;
    float[] spawnRadius = {22, 56};
    float despawnDistance = 84;
    int[] lifetime = {1200, 3600};
    float[] size = {0.28f, 0.62f};
    /** Rolling speed per unit of wind (the steady wind is about 0.1 … 0.6, gusts add up to about 1). */
    float speed = 14;
    /** A desert's wind never quite drops: the least wind they feel. */
    float minWind = 0.2f;
    float maxSpeed = 9;
    float gravity = 0.035f, bounce = 0.42f;
    /** Chance per tick of a hop while rolling (times its speed share) and the hop's upward speed (blocks/tick). */
    float hopChance = 0.025f;
    float[] hopSpeed = {0.1f, 0.28f};
    /** How quickly the wind brings it up to speed on the ground and in the air (share of the difference per tick). */
    float groundGrip = 0.05f, airGrip = 0.022f;
    float rollingFriction = 0.004f;
    /** Chance of getting caught on a catcher plant it rolls into, for how long, and the wind that tears it free. */
    float snagChance = 0.35f;
    int[] snagTicks = {40, 260};
    float releaseWind = 0.85f;
    List<String> biomes = new ArrayList<>(), ground = new ArrayList<>(), catchers = new ArrayList<>();

    transient Set<ResourceLocation> biomeIds = new HashSet<>();
    private transient Matcher groundMatcher = new Matcher(List.of()), catcherMatcher = new Matcher(List.of());

    boolean ground(BlockState state) {
        return groundMatcher.matches(state);
    }

    boolean catcher(BlockState state) {
        return catcherMatcher.matches(state);
    }

    static TumbleweedData load(ResourceManager manager) {
        TumbleweedData data = new TumbleweedData();
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isPresent()) {
            try (Reader reader = resource.get().openAsReader()) {
                TumbleweedData read = GSON.fromJson(reader, TumbleweedData.class);
                if (read != null) data = read;
            } catch (Exception e) {
                BlockLand.LOGGER.warn("[BlockLand] Could not read {}", FILE, e);
            }
        }
        data.resolve();
        return data;
    }

    private void resolve() {
        if (biomes == null) biomes = new ArrayList<>();
        if (ground == null) ground = new ArrayList<>();
        if (catchers == null) catchers = new ArrayList<>();
        biomeIds = new HashSet<>();
        for (String id : biomes) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) biomeIds.add(key);
        }
        groundMatcher = new Matcher(ground);
        catcherMatcher = new Matcher(catchers);
        maxCount = Math.max(0, Math.min(32, maxCount));
        matchCount = Math.max(0, Math.min(32, matchCount));
    }

    /** Block ids and {@code #tags}. */
    private static final class Matcher {
        private final Set<Block> blocks = new HashSet<>();
        private final List<TagKey<Block>> tags = new ArrayList<>();

        Matcher(List<String> entries) {
            for (String entry : entries) {
                boolean tag = entry.startsWith("#");
                ResourceLocation id = ResourceLocation.tryParse(tag ? entry.substring(1) : entry);
                if (id == null) continue;
                if (tag) {
                    tags.add(TagKey.create(Registries.BLOCK, id));
                } else {
                    BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
                }
            }
        }

        boolean matches(BlockState state) {
            if (blocks.contains(state.getBlock())) return true;
            for (TagKey<Block> tag : tags) if (state.is(tag)) return true;
            return false;
        }
    }
}
