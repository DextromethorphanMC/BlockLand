package com.blockland.client.revive;

import com.blockland.revive.ReviveContent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/** Revive antennas on this client (asset spec §51): their renderer, which also blinks the beacon and its light. */
public final class ClientRevive {
    private ClientRevive() {
    }

    public static void init(BusGroup modBus) {
        EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(event ->
                event.registerBlockEntityRenderer(ReviveContent.ANTENNA_ENTITY.get(), ReviveAntennaRenderer::new));
    }
}
