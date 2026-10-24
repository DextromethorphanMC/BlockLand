package com.blockland.network;

import com.blockland.network.packet.BombPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for 枪战爆破 packets, kept apart from the other listeners so the game type can be wired (or left out) on its
 * own. Implemented by the bomb game's router (server) and the client bomb module.
 */
public final class BombPacketListeners {
    private BombPacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onPlant(ServerPlayer player, boolean holding) { }
            @Override public void onDefuse(ServerPlayer player, boolean holding) { }
        };

        void onPlant(ServerPlayer player, boolean holding);

        void onDefuse(ServerPlayer player, boolean holding);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onState(BombPackets.S2CState state) { }
            @Override public void onDetonate(BombPackets.S2CDetonate detonate) { }
        };

        void onState(BombPackets.S2CState state);

        void onDetonate(BombPackets.S2CDetonate detonate);
    }
}
