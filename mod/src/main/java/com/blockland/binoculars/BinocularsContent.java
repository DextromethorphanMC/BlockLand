package com.blockland.binoculars;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Registers the binoculars (望远镜) item and its sound events — independent of the other modules. Looking through them
 * (the zoom, the view, the rangefinder) is the client's optics (`client/binoculars`, `client/scope/Optics`); the item
 * only holds the use, which vanilla syncs, so everyone sees them raised.
 */
public final class BinocularsContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);

    public static final String ITEM_ID = "binoculars";
    /** The item id kits use. */
    public static final String ITEM_KEY = BlockLand.MODID + ":" + ITEM_ID;
    /** Equipment's creative tab ({@code EquipmentContent}): the binoculars are listed with the other gear. */
    private static final ResourceKey<CreativeModeTab> TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, BlockLand.id("equipment"));

    public static final RegistryObject<Item> BINOCULARS = ITEMS.register(ITEM_ID, () -> new BinocularsItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(ITEM_ID)))
            .stacksTo(1)));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json, asset spec §52). */
    public static final List<String> SOUND_EVENTS = List.of("binoculars.raise", "binoculars.lower", "binoculars.zoom");

    private BinocularsContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        BuildCreativeModeTabContentsEvent.getBus(modBus).addListener(event -> {
            if (event.getTabKey().equals(TAB)) event.accept(BINOCULARS);
        });
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
    }

    public static ItemStack stack() {
        return new ItemStack(BINOCULARS.get());
    }

    public static boolean is(ItemStack stack) {
        return stack.getItem() instanceof BinocularsItem;
    }

    /** A sound event of this module by its short name ({@code binoculars.raise}). */
    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
