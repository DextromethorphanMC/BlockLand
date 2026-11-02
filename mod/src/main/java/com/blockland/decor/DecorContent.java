package com.blockland.decor;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseCoralPlantBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Registers the vanilla-style building blocks — the vending machine, the target, the swim ring, grass and stone layers,
 * the corals, the road-side props (rear-view mirror, license plate, oil drums, tires) — their items and a creative tab,
 * independent of the other modules. Models, textures, block states, loot tables and tags come from
 * {@code tools/assets/gen_decor.py} (the props from its {@code gen_decor_props.py}); the ids here and there must agree
 * ({@code tools/assets/check_decor.py} checks).
 */
public final class DecorContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    /** Every item of this module, in creative-tab order. */
    private static final List<RegistryObject<Item>> TAB_ITEMS = new ArrayList<>();

    /** A coral shape (alive {@code <name>_coral}, dead {@code dead_<name>_coral}); keep in step with gen_decor.py. */
    public record Coral(String name, MapColor map) {
        public String alive() {
            return name + "_coral";
        }

        public String dead() {
            return "dead_" + name + "_coral";
        }
    }

    /** An oil drum colour ({@code <colour>_oil_drum}); keep in step with gen_decor_props.py DRUM_COLOURS. */
    public record OilDrum(String colour, MapColor map) {
        public String id() {
            return colour + "_oil_drum";
        }
    }

    public static final List<OilDrum> OIL_DRUMS = List.of(
            new OilDrum("red", MapColor.COLOR_RED),
            new OilDrum("blue", MapColor.COLOR_BLUE),
            new OilDrum("yellow", MapColor.COLOR_YELLOW));

    public static final List<Coral> CORALS = List.of(
            new Coral("staghorn", MapColor.COLOR_ORANGE),
            new Coral("table", MapColor.COLOR_CYAN),
            new Coral("pillar", MapColor.SAND),
            new Coral("brain_dome", MapColor.COLOR_LIGHT_GREEN),
            new Coral("tube_cluster", MapColor.COLOR_MAGENTA),
            new Coral("sea_whip", MapColor.COLOR_RED));

    public static final RegistryObject<Block> VENDING_MACHINE = block("vending_machine", p -> new VendingMachineBlock(p
            .mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.IRON_XYLOPHONE).strength(3.5f, 6.0f)
            .requiresCorrectToolForDrops().sound(SoundType.METAL).lightLevel(state -> 9).noOcclusion()
            .pushReaction(PushReaction.BLOCK)));
    public static final RegistryObject<Block> TARGET_STAND = block("target_stand", p -> new TargetStandBlock(p
            .mapColor(MapColor.WOOD).instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WOOD).noOcclusion()
            .pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> SWIM_RING = BLOCKS.register("swim_ring", () -> new SwimRingBlock(props("swim_ring")
            .mapColor(MapColor.COLOR_RED).strength(0.3f).sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.DESTROY)));
    /** Laid on water like a lily pad (its own item class). */
    public static final RegistryObject<Item> SWIM_RING_ITEM = tab(ITEMS.register("swim_ring",
            () -> new SwimRingItem(SWIM_RING.get(), itemProps("swim_ring"))));
    public static final RegistryObject<Block> GRASS_LAYER = block("grass_layer", p -> new LayerBlock(p
            .mapColor(MapColor.GRASS).strength(0.6f).sound(SoundType.GRASS)));
    public static final RegistryObject<Block> STONE_LAYER = block("stone_layer", p -> new LayerBlock(p
            .mapColor(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM).strength(1.5f, 6.0f).requiresCorrectToolForDrops()
            .sound(SoundType.STONE)));
    public static final RegistryObject<Block> DEAD_REEF_CORAL = block("dead_reef_coral", p -> new ReefCoralBlock(null, p
            .mapColor(MapColor.COLOR_GRAY).instrument(NoteBlockInstrument.BASEDRUM).strength(1.5f, 6.0f)
            .requiresCorrectToolForDrops().sound(SoundType.CORAL_BLOCK).noOcclusion()));
    public static final RegistryObject<Block> REEF_CORAL = block("reef_coral", p -> new ReefCoralBlock(DEAD_REEF_CORAL::get, p
            .mapColor(MapColor.COLOR_PINK).instrument(NoteBlockInstrument.BASEDRUM).strength(1.5f, 6.0f)
            .requiresCorrectToolForDrops().sound(SoundType.CORAL_BLOCK).noOcclusion()));

    public static final RegistryObject<Block> REAR_VIEW_MIRROR = block("rear_view_mirror", p -> new RearViewMirrorBlock(p
            .mapColor(MapColor.COLOR_BLACK).strength(0.5f).sound(SoundType.GLASS).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> LICENSE_PLATE = block("license_plate", p -> new LicensePlateBlock(p
            .mapColor(MapColor.SNOW).strength(0.8f).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK)));
    public static final RegistryObject<Block> TIRE = block("tire", p -> new TireBlock(p
            .mapColor(MapColor.COLOR_BLACK).strength(0.8f).sound(SoundType.WOOL).noOcclusion()));

    static {
        for (OilDrum drum : OIL_DRUMS) {
            block(drum.id(), p -> new OilDrumBlock(p.mapColor(drum.map()).instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .strength(2.0f, 4.0f).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK)));
        }
        for (Coral coral : CORALS) {
            RegistryObject<Block> dead = block(coral.dead(), p -> new BaseCoralPlantBlock(p
                    .mapColor(MapColor.COLOR_GRAY).forceSolidOn().instrument(NoteBlockInstrument.BASEDRUM).noCollission().instabreak()));
            block(coral.alive(), p -> new CoralPlantBlock(dead.get(), p
                    .mapColor(coral.map()).noCollission().instabreak().sound(SoundType.WET_GRASS).pushReaction(PushReaction.DESTROY)));
        }
    }

    private DecorContent() {
    }

    private static BlockBehaviour.Properties props(String id) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, BlockLand.id(id)));
    }

    private static Item.Properties itemProps(String id) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(id))).useBlockDescriptionPrefix();
    }

    /** A block and its plain block item (in the tab). */
    private static RegistryObject<Block> block(String id, Function<BlockBehaviour.Properties, Block> make) {
        RegistryObject<Block> block = BLOCKS.register(id, () -> make.apply(props(id)));
        tab(ITEMS.register(id, () -> new BlockItem(block.get(), itemProps(id))));
        return block;
    }

    private static RegistryObject<Item> tab(RegistryObject<Item> item) {
        TAB_ITEMS.add(item);
        return item;
    }

    public static void register(BusGroup modBus) {
        TABS.register("decor", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.decor"))
                .icon(() -> new ItemStack(VENDING_MACHINE.get()))
                .displayItems((params, output) -> TAB_ITEMS.forEach(item -> output.accept(item.get())))
                .build());
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}
