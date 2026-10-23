package com.blockland.network;

import com.blockland.network.packet.EquipmentPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for equipment packets (grenades, throwing knives, injectors), kept apart from the gun and knife listeners so
 * the equipment feature can be wired (or left out) on its own. Implemented by the server equipment service and the
 * client equipment module.
 */
public final class EquipmentPacketListeners {
    private EquipmentPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, action) -> { };

        void onAction(ServerPlayer player, EquipmentPackets.C2SAction action);
    }

    public interface Client {
        Client NOOP = hit -> { };

        void onHit(EquipmentPackets.S2CHit hit);
    }
}
