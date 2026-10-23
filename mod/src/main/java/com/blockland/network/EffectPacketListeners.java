package com.blockland.network;

import com.blockland.network.packet.EffectPackets;

/** Handlers for equipment effect packets; implemented by the client effect module. */
public final class EffectPacketListeners {
    private EffectPacketListeners() {
    }

    public interface Client {
        Client NOOP = flash -> { };

        void onFlash(EffectPackets.S2CFlash flash);
    }
}
