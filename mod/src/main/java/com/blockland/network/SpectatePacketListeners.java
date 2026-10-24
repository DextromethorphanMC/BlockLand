package com.blockland.network;

import com.blockland.network.packet.SpectatePackets;
import net.minecraft.server.level.ServerPlayer;

/** Handlers for spectating packets: the spectate service (server) and the client spectate module. */
public final class SpectatePacketListeners {
    private SpectatePacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, action) -> { };

        void onControl(ServerPlayer player, SpectatePackets.Control action);
    }

    public interface Client {
        Client NOOP = state -> { };

        void onState(SpectatePackets.S2CState state);
    }
}
