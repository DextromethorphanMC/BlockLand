package com.blockland.network;

import com.blockland.network.packet.ArmorPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for body armour packets (asset spec §50), kept apart from the other listeners so the armour module can be
 * wired (or left out) on its own. Implemented by the server armour service and the client armour module.
 */
public final class ArmorPacketListeners {
    private ArmorPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, insert) -> { };

        void onInsert(ServerPlayer player, ArmorPackets.C2SInsert insert);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override
            public void onInsert(ArmorPackets.S2CInsert insert) {
            }

            @Override
            public void onHit(ArmorPackets.S2CHit hit) {
            }
        };

        void onInsert(ArmorPackets.S2CInsert insert);

        void onHit(ArmorPackets.S2CHit hit);
    }
}
