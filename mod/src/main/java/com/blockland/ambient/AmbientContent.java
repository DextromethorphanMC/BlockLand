package com.blockland.ambient;

import com.blockland.BlockLand;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * What the world's ambient props need registered on both sides (rendering spec §29): the tumbleweed's entity type. Only
 * clients ever make one; it is never saved or summoned.
 */
public final class AmbientContent {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockLand.MODID);

    public static final RegistryObject<EntityType<TumbleweedEntity>> TUMBLEWEED = ENTITIES.register("tumbleweed", () -> EntityType.Builder
            .<TumbleweedEntity>of(TumbleweedEntity::new, MobCategory.MISC)
            .sized(0.6F, 0.6F)
            .noSave()
            .noSummon()
            .fireImmune()
            .clientTrackingRange(4)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id("tumbleweed"))));

    private AmbientContent() {
    }

    public static void register(BusGroup modBus) {
        ENTITIES.register(modBus);
    }
}
