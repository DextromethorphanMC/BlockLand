package com.blockland.gun;

import com.blockland.BlockLand;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
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
 * Registers everything guns need in the game registries — one item per gun and ammo type in the catalog,
 * the {@code blockland:gun} item component, sound events — and offers stack helpers.
 */
public final class GunContent {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    public static final RegistryObject<DataComponentType<GunState>> GUN_STATE = COMPONENTS.register("gun",
            () -> DataComponentType.<GunState>builder().persistent(GunState.CODEC).networkSynchronized(GunState.STREAM_CODEC).build());

    /** Damage type of bullets (data/blockland/damage_type/bullet.json). */
    public static final ResourceKey<DamageType> BULLET = ResourceKey.create(Registries.DAMAGE_TYPE, BlockLand.id("bullet"));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json). */
    public static final List<String> SOUND_EVENTS = List.of(
            "gun.p9.fire", "gun.p9.fire_far", "gun.dry_fire", "gun.mag_release", "gun.mag_out", "gun.mag_in",
            "gun.slide_back", "gun.slide_forward", "gun.slide_release", "gun.inspect_move", "gun.equip", "gun.hammer",
            "gun.shell", "gun.hitmarker", "gun.headshot", "gun.kill", "gun.attach");

    private static final Map<String, RegistryObject<Item>> GUN_ITEMS = new LinkedHashMap<>();
    private static final Map<String, RegistryObject<Item>> AMMO_ITEMS = new LinkedHashMap<>();
    private static final Map<String, RegistryObject<SoundEvent>> SOUND_OBJECTS = new LinkedHashMap<>();

    private GunContent() {
    }

    public static void register(BusGroup modBus) {
        GunCatalog catalog = GunCatalog.get();
        for (GunData.Gun gun : catalog.guns()) {
            GUN_ITEMS.put(gun.id, ITEMS.register(gun.id, () -> new GunItem(gun.id, properties(gun.id)
                    .stacksTo(1)
                    .component(GUN_STATE.get(), GunState.fresh(gun)))));
        }
        for (GunData.Ammo ammo : catalog.ammoTypes()) {
            AMMO_ITEMS.put(ammo.id, ITEMS.register(ammo.item, () -> new AmmoItem(ammo.id, properties(ammo.item).stacksTo(64))));
        }
        for (String name : SOUND_EVENTS) {
            SOUND_OBJECTS.put(name, SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name))));
        }
        TABS.register("guns", () -> CreativeModeTab.builder()
                .title(Component.literal("BlockLand 枪械"))
                .icon(() -> GUN_ITEMS.values().stream().findFirst().map(o -> new ItemStack(o.get())).orElse(ItemStack.EMPTY))
                .displayItems((params, output) -> {
                    GUN_ITEMS.values().forEach(o -> output.accept(o.get()));
                    AMMO_ITEMS.values().forEach(o -> output.accept(o.get()));
                })
                .build());
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }

    private static Item.Properties properties(String path) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, BlockLand.id(path)));
    }

    // ---- stack helpers ------------------------------------------------------------------------------

    public static Optional<GunData.Gun> gunOf(ItemStack stack) {
        return stack.getItem() instanceof GunItem gun ? GunCatalog.get().gun(gun.gunId()) : Optional.empty();
    }

    /** State of a gun stack; guns without the component (e.g. spawned by commands) count as fresh. */
    @Nullable
    public static GunState stateOf(ItemStack stack) {
        if (!(stack.getItem() instanceof GunItem gun)) return null;
        GunState state = stack.get(GUN_STATE.get());
        if (state != null) return state;
        return GunCatalog.get().gun(gun.gunId()).map(GunState::fresh).orElse(null);
    }

    public static Optional<GunStats> statsOf(ItemStack stack) {
        GunState state = stateOf(stack);
        return state == null ? Optional.empty() : GunStats.of(((GunItem) stack.getItem()).gunId(), state);
    }

    /** Writes the state and mirrors the skin into custom_model_data so item models can pick the right look. */
    public static void setState(ItemStack stack, GunState state) {
        stack.set(GUN_STATE.get(), state);
        if ("default".equals(state.skin())) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(state.skin()), List.of()));
        }
    }

    @Nullable
    public static Item ammoItem(String ammoId) {
        RegistryObject<Item> item = AMMO_ITEMS.get(ammoId);
        return item == null ? null : item.get();
    }

    @Nullable
    public static SoundEvent sound(String name) {
        RegistryObject<SoundEvent> sound = SOUND_OBJECTS.get(name);
        return sound == null ? null : sound.get();
    }
}
