package com.blockland.network;

import com.blockland.network.packet.MovementPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for movement packets (the slide), kept apart from the weapon listeners so movement can be wired (or left
 * out) on its own. Implemented by the server slide service and the client movement module.
 */
public final class MovementPacketListeners {
    private MovementPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, slide) -> { };

        void onSlide(ServerPlayer player, MovementPackets.C2SSlide slide);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onSlide(MovementPackets.S2CSlide slide) { }
            @Override public void onRules(MovementPackets.S2CRules rules) { }
        };

        void onSlide(MovementPackets.S2CSlide slide);

        void onRules(MovementPackets.S2CRules rules);
    }
}
