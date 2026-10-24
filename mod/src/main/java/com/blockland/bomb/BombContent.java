package com.blockland.bomb;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers what the bomb needs in the game registries — the {@code blockland:c4} item, the planted bomb entity and the
 * bomb's sound events — independent of the weapon and equipment modules.
 */
public final class BombContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);

    public static final String ITEM_ID = "c4";
    /** The item id kits and the kill feed use. */
    public static final String ITEM_KEY = BlockLand.MODID + ":" + ITEM_ID;

    public static final RegistryObject<Item> C4 = ITEMS.register(ITEM_ID, () -> new BombItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(ITEM_ID)))
            .stacksTo(1)
            .rarity(Rarity.UNCOMMON)));

    public static final RegistryObject<EntityType<PlantedBomb>> PLANTED_BOMB = ENTITIES.register("planted_bomb",
            () -> EntityType.Builder.<PlantedBomb>of(PlantedBomb::new, MobCategory.MISC)
                    .sized(0.45F, 0.14F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id("planted_bomb"))));

    /** Damage type of the explosion (data/blockland/damage_type/bomb.json). */
    public static final ResourceKey<DamageType> BLAST_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("bomb"));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json, asset spec §39). */
    public static final java.util.List<String> SOUND_EVENTS = java.util.List.of(
            "bomb.draw", "bomb.key", "bomb.planted", "bomb.beep", "bomb.beep_final", "bomb.defuse_start", "bomb.defuse",
            "bomb.defused", "bomb.explode", "bomb.explode_far", "bomb.pickup", "bomb.drop");

    private BombContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
    }

    public static boolean isBomb(ItemStack stack) {
        return stack.getItem() instanceof BombItem;
    }

    /** A sound event of this module by its short name ({@code bomb.beep}). */
    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
