package com.blockland.network;

import com.blockland.network.packet.VehiclePackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for vehicle packets, kept apart from the other listeners so vehicles can be wired (or left out) on their own.
 * Implemented by the server vehicle service and the client vehicle module.
 */
public final class VehiclePacketListeners {
    private VehiclePacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onControl(ServerPlayer player, VehiclePackets.C2SControl control) { }
            @Override public void onAction(ServerPlayer player, VehiclePackets.C2SAction action) { }
        };

        void onControl(ServerPlayer player, VehiclePackets.C2SControl control);

        void onAction(ServerPlayer player, VehiclePackets.C2SAction action);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onState(VehiclePackets.S2CState state) { }
            @Override public void onShot(VehiclePackets.S2CShot shot) { }
        };

        void onState(VehiclePackets.S2CState state);

        void onShot(VehiclePackets.S2CShot shot);
    }
}
