package com.blockland.network;

import com.blockland.network.packet.AmbientPackets;

/**
 * Handlers for the ambient packets (rendering spec §29), kept apart from the other listeners. Implemented by the
 * client's ambient module (the server only sends).
 */
public final class AmbientPacketListeners {
    private AmbientPacketListeners() {
    }

    public interface Client {
        Client NOOP = msg -> { };

        void onAmbient(AmbientPackets.S2CAmbient msg);
    }
}
