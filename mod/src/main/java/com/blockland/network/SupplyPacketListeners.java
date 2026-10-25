package com.blockland.network;

import com.blockland.network.packet.SupplyPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for resupply packets (ammo crate, asset spec §46), kept apart from the other listeners so the supply module
 * can be wired (or left out) on its own. Implemented by the server supply service and the client supply module.
 */
public final class SupplyPacketListeners {
    private SupplyPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, use) -> { };

        void onUse(ServerPlayer player, SupplyPackets.C2SUse use);
    }

    public interface Client {
        Client NOOP = resupplied -> { };

        void onResupplied(SupplyPackets.S2CResupplied resupplied);
    }
}
