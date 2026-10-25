package com.blockland.armor;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Registers body armour (asset spec §50) — the plate carrier {@code blockland:vest} (chest-slot equippable, no vanilla
 * armour points and no armour model: the client module draws it on the body and the armour service does the
 * protecting), the armour plate {@code blockland:armor_plate}, the carrier's {@code blockland:vest} component (its
 * plates), the sound events and a creative tab — and offers the stack helpers. Independent of the weapon modules.
 */
public final class ArmorContent {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    public static final String VEST_ID = "vest", PLATE_ID = "armor_plate";
    /** Item ids kits use (the vest at {@code chest}, plates anywhere). */
    public static final String VEST_KEY = BlockLand.MODID + ":" + VEST_ID, PLATE_KEY = BlockLand.MODID + ":" + PLATE_ID;

    /** The plates in a carrier; changed only by the server's armour service. */
    public static final RegistryObject<DataComponentType<VestState>> VEST_STATE = COMPONENTS.register(VEST_ID,
            () -> DataComponentType.<VestState>builder().persistent(VestState.CODEC).networkSynchronized(VestState.STREAM_CODEC).build());

    public static final RegistryObject<Item> VEST = ITEMS.register(VEST_ID, () -> new VestItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(VEST_ID)))
            .stacksTo(1)
            .component(VEST_STATE.get(), VestState.EMPTY)
            .component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST)
                    .setEquipSound(Holder.direct(SoundEvent.createVariableRangeEvent(BlockLand.id("armor.vest_equip"))))
                    .build())));
    public static final RegistryObject<Item> PLATE = ITEMS.register(PLATE_ID, () -> new PlateItem(new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(PLATE_ID)))
            .stacksTo(8)));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json, spec §50). */
    public static final List<String> SOUND_EVENTS = List.of("armor.vest_equip", "armor.plate_draw", "armor.plate_insert",
            "armor.plate_hit", "armor.plate_break");

    private ArmorContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        TABS.register("armor", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.armor"))
                .icon(() -> new ItemStack(VEST.get()))
                .displayItems((params, output) -> {
                    output.accept(VEST.get());
                    output.accept(PLATE.get());
                    ItemStack full = new ItemStack(VEST.get());
                    VestState state = VestState.EMPTY;
                    for (int i = 0; i < ArmorRules.get().maxPlates; i++) state = state.inserted(ArmorRules.get().plateDurability);
                    full.set(VEST_STATE.get(), state);
                    output.accept(full, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
                })
                .build());
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** The carrier the entity wears in its chest slot, or {@link ItemStack#EMPTY}. */
    public static ItemStack wornVest(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof VestItem ? chest : ItemStack.EMPTY;
    }

    /** The plates in a carrier stack (empty for anything else). */
    public static VestState stateOf(ItemStack stack) {
        if (!(stack.getItem() instanceof VestItem)) return VestState.EMPTY;
        VestState state = stack.get(VEST_STATE.get());
        return state == null ? VestState.EMPTY : state;
    }

    public static boolean isPlate(ItemStack stack) {
        return stack.getItem() instanceof PlateItem;
    }

    /** A sound event of this module by its short name ({@code armor.plate_break}). */
    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
