package com.blockland.supply;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;
import java.util.Set;

/**
 * Registers what resupplying needs — the {@code blockland:ammo_crate} block, its item and block entity, the supply
 * sound events and a creative tab — independent of the other modules (the guns are reached through their public
 * helpers only).
 */
public final class SupplyContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    public static final String CRATE_ID = "ammo_crate";

    public static final RegistryObject<Block> AMMO_CRATE = BLOCKS.register(CRATE_ID, () -> new AmmoCrateBlock(
            BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, BlockLand.id(CRATE_ID)))
                    .mapColor(MapColor.COLOR_GREEN).strength(2.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final RegistryObject<Item> AMMO_CRATE_ITEM = ITEMS.register(CRATE_ID, () -> new BlockItem(AMMO_CRATE.get(),
            new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(CRATE_ID))).useBlockDescriptionPrefix()));
    public static final RegistryObject<BlockEntityType<AmmoCrateBlockEntity>> AMMO_CRATE_ENTITY = BLOCK_ENTITIES.register(CRATE_ID,
            () -> new BlockEntityType<>(AmmoCrateBlockEntity::new, Set.of(AMMO_CRATE.get())));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json, spec §46). */
    public static final List<String> SOUND_EVENTS = List.of("supply.crate_open", "supply.crate_close", "supply.rummage",
            "supply.magazine");

    private SupplyContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        TABS.register("supply", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.supply"))
                .icon(() -> new ItemStack(AMMO_CRATE_ITEM.get()))
                .displayItems((params, output) -> output.accept(AMMO_CRATE_ITEM.get()))
                .build());
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }

    /** A sound event of this module by its short name ({@code supply.crate_open}). */
    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
