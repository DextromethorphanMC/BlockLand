package com.blockland.client.impact;

import com.blockland.BlockLand;
import com.google.gson.Gson;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@code assets/blockland/impacts.json}, read on every resource reload, with its rules compiled: which material a block
 * state, an entity or a fluid is (first matching rule, cached per state / type), and the ammo profiles.
 */
final class ImpactCatalog {
    private static final ResourceLocation FILE = BlockLand.id("impacts.json");
    /** Vanilla's sound types by their field name in lower case ({@code metal}, {@code wood}, {@code glass}…). */
    private static final Map<String, SoundType> SOUND_TYPES = soundTypes();

    private static ImpactData data = new ImpactData();
    private static final ImpactData.Material NONE = new ImpactData.Material();
    private static final Map<BlockState, ImpactData.Material> byState = new IdentityHashMap<>();
    private static final Map<Fluid, ImpactData.Material> byFluid = new IdentityHashMap<>();
    private static final Map<String, Predicate<BlockState>> blockTests = new HashMap<>();

    private ImpactCatalog() {
    }

    static void reload(ResourceManager manager) {
        combined.clear();
        ImpactData read = null;
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isPresent()) {
            try (Reader reader = resource.get().openAsReader()) {
                read = new Gson().fromJson(reader, ImpactData.class);
            } catch (Exception e) {
                BlockLand.LOGGER.error("[BlockLand] Could not read {}", FILE, e);
            }
        } else {
            BlockLand.LOGGER.warn("[BlockLand] {} is missing: bullet impacts show nothing", FILE);
        }
        data = read == null ? new ImpactData() : read;
        byState.clear();
        byFluid.clear();
        blockTests.clear();
        for (ImpactData.Rule rule : data.blocks) blockTests.put(rule.match, blockTest(rule.match));
        BlockLand.LOGGER.info("[BlockLand] Impacts: {} materials, {} block rules, {} ammo profiles", data.materials.size(),
                data.blocks.size(), data.ammo.size());
    }

    /** Forgets the compiled rules' answers (tags may differ on the next server). */
    static void clearCaches() {
        byState.clear();
        byFluid.clear();
    }

    static ImpactData data() {
        return data;
    }

    static ImpactData.Material material(String id) {
        ImpactData.Material material = data.materials.get(id);
        return material == null ? NONE : material;
    }

    static ImpactData.Material block(BlockState state) {
        return byState.computeIfAbsent(state, s -> {
            for (ImpactData.Rule rule : data.blocks) {
                Predicate<BlockState> test = blockTests.get(rule.match);
                if (test != null && test.test(s)) return material(rule.material);
            }
            return material(data.fallbackBlock);
        });
    }

    static ImpactData.Material entity(Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        for (ImpactData.Rule rule : data.entities) {
            String m = rule.match;
            boolean hit = switch (m) {
                case "any" -> true;
                case "vehicle" -> entity instanceof VehicleEntity;
                // an armour value like a full iron set: rounds clang off it
                case "armored" -> entity instanceof LivingEntity living && living.getArmorValue() >= 10;
                case "living" -> entity instanceof LivingEntity;
                default -> m.startsWith("id:") && m.substring(3).equals(id);
            };
            if (hit) return material(rule.material);
        }
        return material(data.fallbackEntity);
    }

    @Nullable
    static ImpactData.Material fluid(FluidState state) {
        if (state.isEmpty()) return null;
        return byFluid.computeIfAbsent(state.getType(), fluid -> {
            String id = BuiltInRegistries.FLUID.getKey(fluid).toString();
            for (ImpactData.Rule rule : data.fluids) {
                String m = rule.match;
                if (m.startsWith("id:") && m.substring(3).equals(id)) return material(rule.material);
                if (m.startsWith("tag:")) {
                    ResourceLocation tag = ResourceLocation.tryParse(m.substring(4));
                    if (tag != null && state.is(TagKey.create(Registries.FLUID, tag))) return material(rule.material);
                }
            }
            return NONE;
        });
    }

    /**
     * The profile of an ammo id; {@code base+modifier} (a caliber and its round, e.g. {@code rifle+incendiary}) is the
     * caliber's profile with the modifier's numbers multiplied in (a modifier names only what it changes, the rest is 1).
     */
    static ImpactData.Ammo ammo(@Nullable String id) {
        int plus = id == null ? -1 : id.indexOf('+');
        if (plus > 0) {
            ImpactData.Ammo base = ammo(id.substring(0, plus));
            ImpactData.Ammo modifier = data.ammo.get(id.substring(plus + 1));
            return modifier == null ? base : combined.computeIfAbsent(id, key -> ImpactData.Ammo.times(base, modifier));
        }
        ImpactData.Ammo ammo = id == null ? null : data.ammo.get(id);
        if (ammo == null) ammo = data.ammo.get(data.fallbackAmmo);
        return ammo == null ? new ImpactData.Ammo() : ammo;
    }

    /** {@code base+modifier} profiles made so far (cleared on reload). */
    private static final java.util.Map<String, ImpactData.Ammo> combined = new java.util.concurrent.ConcurrentHashMap<>();

    private static Predicate<BlockState> blockTest(String match) {
        int colon = match.indexOf(':');
        if (colon < 0) return state -> false;
        String kind = match.substring(0, colon), value = match.substring(colon + 1);
        switch (kind) {
            case "id" -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                return block == null ? state -> false : state -> state.is(block);
            }
            case "tag" -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id == null) return state -> false;
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
                return state -> state.is(tag);
            }
            case "sound" -> {
                SoundType type = SOUND_TYPES.get(value.toLowerCase(Locale.ROOT));
                return type == null ? state -> false : state -> state.getSoundType() == type;
            }
            default -> {
                BlockLand.LOGGER.warn("[BlockLand] Impacts: unknown block rule {}", match);
                return state -> false;
            }
        }
    }

    private static Map<String, SoundType> soundTypes() {
        Map<String, SoundType> types = new HashMap<>();
        for (Field field : SoundType.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != SoundType.class) continue;
            try {
                types.put(field.getName().toLowerCase(Locale.ROOT), (SoundType) field.get(null));
            } catch (IllegalAccessException ignored) {
                // public static fields: always readable
            }
        }
        return types;
    }

    /** {@code #RRGGBB} → 0xRRGGBB (grey when broken). */
    static int rgb(@Nullable String hex) {
        if (hex == null || hex.isEmpty()) return 0x8A8A8A;
        try {
            return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0x8A8A8A;
        }
    }

    static boolean hasData() {
        return !data.materials.isEmpty();
    }
}
