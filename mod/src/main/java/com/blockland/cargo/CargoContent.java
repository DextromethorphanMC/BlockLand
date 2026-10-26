package com.blockland.cargo;

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
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the shipping containers (集装箱): one full-cube block per colour, their items and a creative tab —
 * independent of the other modules. How neighbouring containers join up (the frame only round the outside of a wall of
 * them) is not this module's business: the connected textures (`client/ctm`, rendering spec §25) do it from data
 * ({@code assets/blockland/ctm/container_<colour>.json}, textures from {@code tools/assets/gen_containers.py}).
 */
public final class CargoContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    /** A container colour: block id {@code container_<name>}. Keep in step with {@code gen_containers.py}. */
    public record Colour(String name, MapColor map) {
        public String blockId() {
            return "container_" + name;
        }
    }

    public static final List<Colour> COLOURS = List.of(
            new Colour("red", MapColor.COLOR_RED),
            new Colour("orange", MapColor.COLOR_ORANGE),
            new Colour("yellow", MapColor.COLOR_YELLOW),
            new Colour("green", MapColor.COLOR_GREEN),
            new Colour("blue", MapColor.COLOR_BLUE),
            new Colour("white", MapColor.SNOW),
            new Colour("gray", MapColor.COLOR_GRAY),
            new Colour("brown", MapColor.COLOR_BROWN));

    public static final List<RegistryObject<Block>> CONTAINERS = new ArrayList<>();
    public static final List<RegistryObject<Item>> CONTAINER_ITEMS = new ArrayList<>();

    static {
        for (Colour colour : COLOURS) {
            String id = colour.blockId();
            RegistryObject<Block> block = BLOCKS.register(id, () -> new Block(BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, BlockLand.id(id)))
                    .mapColor(colour.map()).instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .strength(5.0f, 6.0f).requiresCorrectToolForDrops().sound(SoundType.METAL)));
            CONTAINERS.add(block);
            CONTAINER_ITEMS.add(ITEMS.register(id, () -> new BlockItem(block.get(),
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(id))).useBlockDescriptionPrefix())));
        }
    }

    private CargoContent() {
    }

    public static void register(BusGroup modBus) {
        TABS.register("cargo", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.cargo"))
                .icon(() -> new ItemStack(CONTAINER_ITEMS.get(0).get()))
                .displayItems((params, output) -> CONTAINER_ITEMS.forEach(item -> output.accept(item.get())))
                .build());
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}
