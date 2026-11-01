package com.blockland.vehicle;

import com.blockland.BlockLand;
import com.blockland.effect.ActionLocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers what vehicles need — one entity type per vehicle in {@code vehicles.json} (all {@link VehicleEntity}), the
 * cannon shell, a spawn item per vehicle, their sound events ({@code vehicle.<id>.<key>}) and a creative tab —
 * independent of the other modules (contract: tools/vehicles/VEHICLE_SPEC.md). Riders working the vehicle (driver,
 * gunners) or sitting inside its hull have their hands locked ({@link ActionLocks}): their own weapons stay quiet.
 */
public final class VehicleContent {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockLand.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlockLand.MODID);

    /** Entity types by vehicle id, in the data's order. */
    public static final Map<String, RegistryObject<EntityType<VehicleEntity>>> TYPES = new LinkedHashMap<>();
    /** Spawn items by vehicle id. */
    public static final Map<String, RegistryObject<Item>> ITEMS_BY_ID = new LinkedHashMap<>();

    public static final RegistryObject<EntityType<VehicleShell>> SHELL = ENTITIES.register("vehicle_shell", () -> EntityType.Builder
            .<VehicleShell>of(VehicleShell::new, MobCategory.MISC)
            .sized(0.25F, 0.25F)
            .clientTrackingRange(10)
            .updateInterval(1)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id("vehicle_shell"))));

    static {
        for (VehicleData.Vehicle v : VehicleCatalog.get().vehicles()) {
            String id = v.id;
            RegistryObject<EntityType<VehicleEntity>> type = ENTITIES.register(id, () -> EntityType.Builder
                    .<VehicleEntity>of(VehicleEntity::new, MobCategory.MISC)
                    .sized(v.size[0], v.size[1])
                    .clientTrackingRange(v.trackingRange)
                    .updateInterval(1)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id(id))));
            TYPES.put(id, type);
            boolean boat = v.kind() == VehicleData.Kind.BOAT;
            ITEMS_BY_ID.put(id, ITEMS.register(id, () -> new VehicleItem(type, boat, new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, BlockLand.id(id))).stacksTo(1))));
        }
    }

    private VehicleContent() {
    }

    public static void register(BusGroup modBus) {
        for (VehicleData.Vehicle v : VehicleCatalog.get().vehicles()) {
            for (String key : v.sounds) {
                String name = v.sound(key);
                SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
            }
        }
        TABS.register("vehicles", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockland.vehicles"))
                .icon(() -> ITEMS_BY_ID.isEmpty() ? ItemStack.EMPTY : new ItemStack(ITEMS_BY_ID.values().iterator().next().get()))
                .displayItems((params, output) -> ITEMS_BY_ID.values().forEach(item -> output.accept(item.get())))
                .build());
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
        ActionLocks.addLock(VehicleEntity::handsBusy);
    }

    /** The spawn item of a vehicle (empty if it has none). */
    public static ItemStack item(String id) {
        RegistryObject<Item> item = ITEMS_BY_ID.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item.get());
    }
}
