package com.blockland.client.thirdperson;

import com.blockland.BlockLand;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import java.lang.reflect.Field;

/**
 * Weapons (guns, knives, equipment) in third person, dropped and in item frames as their full 3D models — the same GLB,
 * attachments, skin and sprays as the viewmodel — instead of the low-poly held models (asset spec §37). The item
 * definitions select the {@code blockland:weapon} special model for every context but the GUI icon and first person
 * (where the viewmodel draws); the held model stays their {@code base}, so its display transforms place the weapon in
 * the hand and its texture gives the break particles.
 */
public final class ThirdPersonWeapons {
    /** The special item model type (item definitions: {@code "model": {"type": "blockland:weapon"}}). */
    public static final ResourceLocation TYPE = BlockLand.id("weapon");

    private ThirdPersonWeapons() {
    }

    public static void init(BusGroup modBus) {
        register();
        // the weapons' models and the held boxes come from the resource packs
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> ThirdPersonDraw.clear()));
    }

    /**
     * Adds the type to vanilla's special model registry. Vanilla fills it during client bootstrap (before mods load) and
     * reads it when item models load (after mods load); it offers no registration hook, hence the reflection. Without
     * it the item definitions naming the type fail to load and the weapons show the missing model, so this is logged
     * loudly.
     */
    @SuppressWarnings("unchecked")
    private static void register() {
        try {
            Field field = SpecialModelRenderers.class.getDeclaredField("ID_MAPPER");
            field.setAccessible(true);
            var mapper = (ExtraCodecs.LateBoundIdMapper<ResourceLocation, MapCodec<? extends SpecialModelRenderer.Unbaked>>) field.get(null);
            mapper.put(TYPE, WeaponItemModel.Unbaked.MAP_CODEC);
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockLand.LOGGER.error("[BlockLand] Cannot register the {} item model: weapons outside the GUI show the missing model", TYPE, e);
        }
    }
}
