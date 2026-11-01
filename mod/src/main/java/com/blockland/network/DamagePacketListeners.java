package com.blockland.network;

import com.blockland.network.packet.DamagePackets;

/**
 * Handlers for the damage direction packets (UI spec §3.5), kept apart from the other listeners. Implemented by the
 * client's damage indicator (the server only sends).
 */
public final class DamagePacketListeners {
    private DamagePacketListeners() {
    }

    public interface Client {
        Client NOOP = msg -> { };

        void onDamage(DamagePackets.S2CDamage msg);
    }
}
