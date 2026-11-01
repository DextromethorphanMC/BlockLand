package com.blockland.equipment;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import com.blockland.craft.Build;
import com.blockland.craft.CraftCatalog;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registers what equipment needs in the game registries — one item per entry of the catalog, the
 * {@code blockland:equipment} item component (the item's 工匠 build: skin and charm), the {@code blockland:lit} switch of
 * the items that toggle (the flashlight; {@code graphics/item_lights.json} names it as the light's switch), the equipment
 * sound events and a creative tab — and offers stack helpers. Independent of the gun and knife modules.
 */
public final class EquipmentContent {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    /** The item's build (skin + charm); changed only by the server's craft service. */
    public static final RegistryObject<DataComponentType<Build>> EQUIPMENT_BUILD = COMPONENTS.register("equipment",
            () -> DataComponentType.<Build>builder().persistent(Build.CODEC).networkSynchronized(Build.STREAM_CODEC).build());

    /** Switched on (the flashlight): synced to every client, so everyone's held light shines; changed only by the server. */
    public static final RegistryObject<DataComponentType<Boolean>> LIT = COMPONENTS.register("lit",
            () -> DataComponentType.<Boolean>builder().persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL).build());

    /** Damage type of throwing knife hits (data/blockland/damage_type/throwing_knife.json). */
    public static final ResourceKey<DamageType> THROWING_KNIFE = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("throwing_knife"));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json, spec §36). */
    public static final List<String> SOUND_EVENTS = List.of(
            "equipment.equip", "equipment.pin", "equipment.spoon", "equipment.throw", "equipment.lob", "equipment.bounce",
            "equipment.frag_explode", "equipment.flash_bang", "equipment.flash_ring", "equipment.smoke_pop",
            "equipment.smoke_hiss", "equipment.molotov_light", "equipment.molotov_shatter", "equipment.fire_loop",
            "equipment.knife_throw", "equipment.knife_hit_flesh", "equipment.knife_hit_block", "equipment.adrenaline_cap",
            "equipment.adrenaline_inject", "equipment.heartbeat", "equipment.flashlight_on", "equipment.flashlight_off");

    private static final Map<String, RegistryObject<Item>> ITEMS_BY_ID = new LinkedHashMap<>();

    private EquipmentContent() {
    }

    public static void register(BusGroup modBus) {
        CraftCatalog.addSource(() -> EquipmentCatalog.get().items());
        for (EquipmentData.Equipment item : EquipmentCatalog.get().items()) {
            ITEMS_BY_ID.put(item.id, ITEMS.register(item.id, () -> new EquipmentItem(item.id,
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(item.id)))
                            .stacksTo(Math.max(1, Math.min(64, item.stack)))
                            .component(EQUIPMENT_BUILD.get(), item.defaultBuild()))));
        }
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        TABS.register("equipment", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.equipment"))
                .icon(() -> ITEMS_BY_ID.values().stream().findFirst().map(o -> new ItemStack(o.get())).orElse(ItemStack.EMPTY))
                .displayItems((params, output) -> ITEMS_BY_ID.values().forEach(o -> output.accept(o.get())))
                .build());
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    public static Optional<EquipmentData.Equipment> equipmentOf(ItemStack stack) {
        return stack.getItem() instanceof EquipmentItem item ? EquipmentCatalog.get().item(item.equipmentId()) : Optional.empty();
    }

    /** The registered item of an equipment id, or null. */
    @Nullable
    public static Item item(String id) {
        RegistryObject<Item> item = ITEMS_BY_ID.get(id);
        return item == null || !item.isPresent() ? null : item.get();
    }

    /** The item's build; stacks without the component (e.g. from commands) have the default one. */
    @Nullable
    public static Build buildOf(ItemStack stack) {
        if (!(stack.getItem() instanceof EquipmentItem item)) return null;
        Build build = stack.get(EQUIPMENT_BUILD.get());
        if (build != null) return build;
        return EquipmentCatalog.get().item(item.equipmentId()).map(EquipmentData.Equipment::defaultBuild).orElse(null);
    }

    /** Whether the stack is switched on (the flashlight). */
    public static boolean isLit(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(LIT.get()));
    }

    /** Server: switches the stack on or off; returns whether it is on now. */
    public static boolean toggleLit(ItemStack stack) {
        boolean on = !isLit(stack);
        if (on) stack.set(LIT.get(), true);
        else stack.remove(LIT.get());
        return on;
    }

    /** Writes the build and mirrors the skin into custom_model_data so item models can pick the right look. */
    public static void setBuild(ItemStack stack, Build build) {
        stack.set(EQUIPMENT_BUILD.get(), build);
        if ("default".equals(build.skin())) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(build.skin()), List.of()));
        }
    }

    /** A sound event by id ({@code namespace:path}); null only when the id is malformed. */
    @Nullable
    public static SoundEvent sound(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : Sounds.byId(location);
    }

    /** A sound event of this module by its short name ({@code equipment.pin}). */
    @Nullable
    public static SoundEvent own(String name) {
        return sound(BlockLand.MODID + ":" + name);
    }
}
