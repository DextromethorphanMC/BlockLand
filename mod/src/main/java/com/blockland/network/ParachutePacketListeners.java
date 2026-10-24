package com.blockland.network;

import com.blockland.network.packet.ParachutePackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for parachute packets, kept apart from the other listeners so the parachute can be wired (or left out) on
 * its own. Implemented by the server parachute service and the client parachute module.
 */
public final class ParachutePacketListeners {
    private ParachutePacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, toggle) -> { };

        void onToggle(ServerPlayer player, ParachutePackets.C2SToggle toggle);
    }

    public interface Client {
        Client NOOP = state -> { };

        void onState(ParachutePackets.S2CState state);
    }
}
