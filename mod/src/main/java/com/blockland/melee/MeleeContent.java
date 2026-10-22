package com.blockland.melee;

import com.blockland.BlockLand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * Registers what knives need in the game registries — one item per knife in the catalog, the knife sound events and
 * a creative tab — and offers stack helpers. Independent of the gun module: sounds of other modules (hit markers)
 * are referenced by id in the data, not by class.
 */
public final class MeleeContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

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
        for (MeleeData.Knife knife : MeleeCatalog.get().knives()) {
            KNIFE_ITEMS.put(knife.id, ITEMS.register(knife.id, () -> new KnifeItem(knife.id,
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(knife.id))).stacksTo(1))));
        }
        for (String name : SOUND_EVENTS) {
            SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        }
        TABS.register("melee", () -> CreativeModeTab.builder()
                .title(Component.literal("BlockLand 近战"))
                .icon(() -> KNIFE_ITEMS.values().stream().findFirst().map(o -> new ItemStack(o.get())).orElse(ItemStack.EMPTY))
                .displayItems((params, output) -> KNIFE_ITEMS.values().forEach(o -> output.accept(o.get())))
                .build());
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    public static Optional<MeleeData.Knife> knifeOf(ItemStack stack) {
        return stack.getItem() instanceof KnifeItem knife ? MeleeCatalog.get().knife(knife.knifeId()) : Optional.empty();
    }

    /** A sound event by id ({@code namespace:path}), null when unknown. */
    @Nullable
    public static SoundEvent sound(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(location).orElse(null);
    }
}
