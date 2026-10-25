package com.blockland.network;

import com.blockland.network.packet.MovementPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for movement packets (slide, prone, dive), kept apart from the weapon listeners so movement can be wired
 * (or left out) on its own. Implemented by the server action service and the client movement module.
 */
public final class MovementPacketListeners {
    private MovementPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, action) -> { };

        void onAction(ServerPlayer player, MovementPackets.C2SAction action);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onAction(MovementPackets.S2CAction action) { }
            @Override public void onRules(MovementPackets.S2CRules rules) { }
        };

        void onAction(MovementPackets.S2CAction action);

        void onRules(MovementPackets.S2CRules rules);
    }
}
