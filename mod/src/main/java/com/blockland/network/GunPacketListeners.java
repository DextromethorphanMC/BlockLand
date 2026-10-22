package com.blockland.network;

import com.blockland.network.packet.GunPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for gun packets, kept apart from the lobby listeners so the gun feature can be wired (or left out)
 * on its own. Implemented by the server gun service and the client gun module.
 */
public final class GunPacketListeners {
    private GunPacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onFire(ServerPlayer player, GunPackets.C2SFire fire) { }
            @Override public void onReload(ServerPlayer player, int slot) { }
            @Override public void onGunsmith(ServerPlayer player, GunPackets.C2SGunsmith request) { }
        };

        void onFire(ServerPlayer player, GunPackets.C2SFire fire);

        void onReload(ServerPlayer player, int slot);

        void onGunsmith(ServerPlayer player, GunPackets.C2SGunsmith request);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onHit(GunPackets.S2CHit hit) { }
            @Override public void onShot(GunPackets.S2CShot shot) { }
        };

        void onHit(GunPackets.S2CHit hit);

        void onShot(GunPackets.S2CShot shot);
    }
}
