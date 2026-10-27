package com.blockland.client.decor;

import com.blockland.decor.DecorContent;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.GrassColor;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/** The decor blocks on this client: the grass layers take the biome's grass colour (as the grass block's top does). */
public final class ClientDecor {
    private ClientDecor() {
    }

    public static void init(BusGroup modBus) {
        RegisterColorHandlersEvent.Block.getBus(modBus).addListener(event -> event.register(
                (state, level, pos, tint) -> level != null && pos != null ? BiomeColors.getAverageGrassColor(level, pos) : GrassColor.getDefaultColor(),
                DecorContent.GRASS_LAYER.get()));
    }
}
