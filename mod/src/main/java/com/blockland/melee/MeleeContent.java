package com.blockland.melee;

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
 * Registers what knives need in the game registries — one item per knife in the catalog, the {@code blockland:knife}
 * item component (the knife's 工匠 build: skin and charm), the knife sound events and a creative tab — and offers
 * stack helpers. Independent of the gun module: sounds of other modules (hit markers)
 * are referenced by id in the data, not by class.
 */
public final class MeleeContent {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    /** The knife's build (skin + charm); changed only by the server's craft service. */
    public static final RegistryObject<DataComponentType<Build>> KNIFE_BUILD = COMPONENTS.register("knife",
            () -> DataComponentType.<Build>builder().persistent(Build.CODEC).networkSynchronized(Build.STREAM_CODEC).build());

    /** Damage type of knife hits (data/blockland/damage_type/knife.json). */
    public static final ResourceKey<DamageType> KNIFE = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("knife"));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json, spec §13). */
    public static final List<String> SOUND_EVENTS = List.of(
            "knife.equip", "knife.open", "knife.flip", "knife.catch", "knife.swing", "knife.swing_heavy",
            "knife.hit_flesh", "knife.hit_wall");

    private static final Map<String, RegistryObject<Item>> KNIFE_ITEMS = new LinkedHashMap<>();

    private MeleeContent() {
    }

    public static void register(BusGroup modBus) {
        CraftCatalog.addSource(() -> MeleeCatalog.get().knives());
        for (MeleeData.Knife knife : MeleeCatalog.get().knives()) {
            KNIFE_ITEMS.put(knife.id, ITEMS.register(knife.id, () -> new KnifeItem(knife.id,
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(knife.id))).stacksTo(1)
                            .component(KNIFE_BUILD.get(), knife.defaultBuild()))));
        }
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        TABS.register("melee", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.melee"))
                .icon(() -> KNIFE_ITEMS.values().stream().findFirst().map(o -> new ItemStack(o.get())).orElse(ItemStack.EMPTY))
                .displayItems((params, output) -> KNIFE_ITEMS.values().forEach(o -> output.accept(o.get())))
                .build());
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    public static Optional<MeleeData.Knife> knifeOf(ItemStack stack) {
        return stack.getItem() instanceof KnifeItem knife ? MeleeCatalog.get().knife(knife.knifeId()) : Optional.empty();
    }

    /** The knife's build; knives without the component (e.g. from commands) have the default one. */
    @Nullable
    public static Build buildOf(ItemStack stack) {
        if (!(stack.getItem() instanceof KnifeItem knife)) return null;
        Build build = stack.get(KNIFE_BUILD.get());
        if (build != null) return build;
        return MeleeCatalog.get().knife(knife.knifeId()).map(MeleeData.Knife::defaultBuild).orElse(null);
    }

    /** Writes the build and mirrors the skin into custom_model_data so item models can pick the right look. */
    public static void setBuild(ItemStack stack, Build build) {
        stack.set(KNIFE_BUILD.get(), build);
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
}
