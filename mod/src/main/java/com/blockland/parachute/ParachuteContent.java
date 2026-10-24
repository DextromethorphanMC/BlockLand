package com.blockland.parachute;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Registers what the parachute needs — the {@code blockland:parachute} item (chest-slot equippable, no armour model:
 * the pack and the canopy are drawn by the client module) and its sound events — independent of the other modules.
 */
public final class ParachuteContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);

    public static final String ITEM_ID = "parachute";
    /** The item id kits use (put it at {@code chest}). */
    public static final String ITEM_KEY = BlockLand.MODID + ":" + ITEM_ID;

    public static final RegistryObject<Item> PARACHUTE = ITEMS.register(ITEM_ID, () -> new ParachuteItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(ITEM_ID)))
            .stacksTo(1)
            .component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST)
                    .setEquipSound(Holder.direct(SoundEvent.createVariableRangeEvent(BlockLand.id("parachute.equip"))))
                    .build())));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json). */
    public static final List<String> SOUND_EVENTS = List.of("parachute.open", "parachute.close", "parachute.wind", "parachute.equip");

    private ParachuteContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
    }

    public static ItemStack stack() {
        return new ItemStack(PARACHUTE.get());
    }

    /** A sound event of this module by its short name ({@code parachute.open}). */
    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
