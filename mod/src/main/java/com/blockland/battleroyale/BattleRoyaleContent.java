package com.blockland.battleroyale;

import com.blockland.BlockLand;
import com.blockland.common.Sounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * What 大逃杀 needs in the game registries on both sides — the drop plane and the mode's sound events — and the rule
 * that nobody leaves the plane on their own (sneaking off is refused on the server; the game releases jumpers).
 */
public final class BattleRoyaleContent {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockLand.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BlockLand.MODID);

    public static final RegistryObject<EntityType<BrPlane>> PLANE = ENTITIES.register("br_plane",
            () -> EntityType.Builder.<BrPlane>of(BrPlane::new, MobCategory.MISC)
                    .sized(3.0F, 2.0F)
                    .clientTrackingRange(16)
                    .updateInterval(20)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, BlockLand.id("br_plane"))));

    /** Sound events in the {@code blockland} namespace (assets/blockland/sounds.json). */
    public static final List<String> SOUND_EVENTS = List.of("battleroyale.plane_engine", "battleroyale.jump", "battleroyale.wind",
            "battleroyale.zone_shrink");

    private BattleRoyaleContent() {
    }

    public static void register(BusGroup modBus) {
        for (String name : SOUND_EVENTS) SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(BlockLand.id(name)));
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        EntityMountEvent.BUS.addListener(event -> event.isDismounting() && !event.getLevel().isClientSide()
                && event.getEntityBeingMounted() instanceof BrPlane plane && !plane.allowsDismount(event.getEntityMounting()));
    }

    public static SoundEvent sound(String name) {
        return Sounds.byId(BlockLand.id(name));
    }
}
