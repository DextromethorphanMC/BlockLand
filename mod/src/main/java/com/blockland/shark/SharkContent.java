package com.blockland.shark;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Registers the shark (鲨鱼, shark spec) — its entity type, attributes, spawn rules, spawn egg and sound events —
 * independent of the other modules. Where it spawns is data: {@code data/blockland/forge/biome_modifier/shark_spawns.json}.
 */
public final class SharkContent {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);

    public static final String BITE = "shark.bite", HURT = "shark.hurt", DEATH = "shark.death", SWIM = "shark.swim", FLOP = "shark.flop";
    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json). */
    public static final List<String> SOUND_EVENTS = List.of(BITE, HURT, DEATH, SWIM, FLOP);

    public static final RegistryObject<EntityType<Shark>> SHARK = ENTITIES.register("shark", () -> EntityType.Builder
            .<Shark>of(Shark::new, MobCategory.WATER_CREATURE)
            .sized(1.3F, 0.9F)
            .eyeHeight(0.55F)
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id("shark"))));

    public static final RegistryObject<Item> SPAWN_EGG = ITEMS.register("shark_spawn_egg", () -> new SpawnEggItem(SHARK.get(),
            new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id("shark_spawn_egg")))));

    private SharkContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        EntityAttributeCreationEvent.getBus(modBus).addListener(event -> event.put(SHARK.get(), Shark.createAttributes().build()));
        SpawnPlacementRegisterEvent.getBus(modBus).addListener(event -> event.register(SHARK.get(), SpawnPlacementTypes.IN_WATER,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Shark::checkSpawnRules, SpawnPlacementRegisterEvent.Operation.REPLACE));
        BuildCreativeModeTabContentsEvent.getBus(modBus).addListener(event -> {
            if (event.getTabKey().equals(CreativeModeTabs.SPAWN_EGGS)) event.accept(SPAWN_EGG);
        });
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
    }

    /** A sound event of this module by its short name ({@code shark.bite}). */
    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
