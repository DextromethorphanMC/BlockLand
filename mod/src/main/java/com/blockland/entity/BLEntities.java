package com.blockland.entity;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** BlockLand's entity types: creatures used as morph models and the projectiles of skills. */
public final class BLEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockLand.MODID);

    public static final RegistryObject<EntityType<SlimeLicker>> SLIME_LICKER = TYPES.register("slime_licker", () -> EntityType.Builder
            .<SlimeLicker>of(SlimeLicker::new, MobCategory.MONSTER)
            .sized(0.6F, 1.95F)
            .eyeHeight(1.74F)
            .clientTrackingRange(8)
            .build(key("slime_licker")));

    public static final RegistryObject<EntityType<SlimeShot>> SLIME_SHOT = TYPES.register("slime_shot", () -> EntityType.Builder
            .<SlimeShot>of(SlimeShot::new, MobCategory.MISC)
            .sized(0.3F, 0.3F)
            .clientTrackingRange(4)
            .updateInterval(10)
            .build(key("slime_shot")));

    /** 枪械瞄准练习's fake players: player-sized, drawn with the player model. */
    public static final RegistryObject<EntityType<PracticeTarget>> PRACTICE_TARGET = TYPES.register("practice_target", () -> EntityType.Builder
            .<PracticeTarget>of(PracticeTarget::new, MobCategory.MISC)
            .sized(0.6F, 1.8F)
            .eyeHeight(1.62F)
            .clientTrackingRange(10)
            .build(key("practice_target")));

    /** Grenades, molotovs and throwing knives in flight / at rest / as active areas (equipment module). */
    public static final RegistryObject<EntityType<com.blockland.equipment.ThrownEquipment>> THROWN_EQUIPMENT = TYPES.register("thrown_equipment",
            () -> EntityType.Builder.<com.blockland.equipment.ThrownEquipment>of(com.blockland.equipment.ThrownEquipment::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build(key("thrown_equipment")));

    private BLEntities() {
    }

    public static void register(BusGroup modBus) {
        TYPES.register(modBus);
        EntityAttributeCreationEvent.getBus(modBus).addListener(event -> {
            event.put(SLIME_LICKER.get(), Zombie.createAttributes().build());
            event.put(PRACTICE_TARGET.get(), PracticeTarget.createAttributes().build());
        });
    }

    private static ResourceKey<EntityType<?>> key(String path) {
        return ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id(path));
    }
}
