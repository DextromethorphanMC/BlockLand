package com.blockland.network;

import com.blockland.network.packet.CheckInPackets;

import java.util.UUID;

/**
 * Handlers for the lobby's daily check-in packets, kept apart from the other listeners. Requests arrive by player id
 * (they are sent from the lobby, where there is no {@code ServerPlayer}).
 */
public final class CheckInPacketListeners {
    private CheckInPacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onRequest(UUID player) { }
            @Override public void onClaim(UUID player) { }
        };

        void onRequest(UUID player);

        void onClaim(UUID player);
    }

    public interface Client {
        Client NOOP = state -> { };

        void onState(CheckInPackets.S2CState state);
    }
}
