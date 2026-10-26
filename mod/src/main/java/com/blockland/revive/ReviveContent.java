package com.blockland.revive;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;

/**
 * Registers the revive antenna (复活天线): the two-block-tall {@code blockland:revive_antenna} block, its item, the
 * block entity of its lower half and a creative tab — independent of the other modules. For now it is only decoration.
 * It occupies two blocks, so it tells the placement preview its footprint ({@code common/block/MultiBlockFootprint}).
 */
public final class ReviveContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    public static final String ANTENNA_ID = "revive_antenna";

    public static final RegistryObject<Block> ANTENNA = BLOCKS.register(ANTENNA_ID, () -> new ReviveAntennaBlock(
            BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, BlockLand.id(ANTENNA_ID)))
                    .mapColor(MapColor.COLOR_GREEN).strength(2.0f).sound(SoundType.METAL).noOcclusion()
                    .pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Item> ANTENNA_ITEM = ITEMS.register(ANTENNA_ID, () -> new BlockItem(ANTENNA.get(),
            new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(ANTENNA_ID))).useBlockDescriptionPrefix()));
    public static final RegistryObject<BlockEntityType<ReviveAntennaBlockEntity>> ANTENNA_ENTITY = BLOCK_ENTITIES.register(ANTENNA_ID,
            () -> new BlockEntityType<>(ReviveAntennaBlockEntity::new, Set.of(ANTENNA.get())));

    private ReviveContent() {
    }

    public static void register(BusGroup modBus) {
        TABS.register("revive", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.revive"))
                .icon(() -> new ItemStack(ANTENNA_ITEM.get()))
                .displayItems((params, output) -> output.accept(ANTENNA_ITEM.get()))
                .build());
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
