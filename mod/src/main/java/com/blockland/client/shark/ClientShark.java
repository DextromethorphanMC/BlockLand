package com.blockland.client.shark;

import com.blockland.shark.SharkContent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/** The shark on this client (shark spec §4): its renderer. Everything else it does is the shared entity's. */
public final class ClientShark {
    private ClientShark() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event -> event.registerEntityRenderer(SharkContent.SHARK.get(), SharkRenderer::new));
    }
}
