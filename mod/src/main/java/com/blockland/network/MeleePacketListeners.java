package com.blockland.network;

import com.blockland.network.packet.MeleePackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for knife packets, kept apart from the lobby and gun listeners so the melee feature can be wired (or left
 * out) on its own. Implemented by the server melee service and the client knife module.
 */
public final class MeleePacketListeners {
    private MeleePacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, attack) -> { };

        void onAttack(ServerPlayer player, MeleePackets.C2SAttack attack);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onHit(MeleePackets.S2CHit hit) { }
            @Override public void onImpact(MeleePackets.S2CImpact impact) { }
        };

        void onHit(MeleePackets.S2CHit hit);

        void onImpact(MeleePackets.S2CImpact impact);
    }
}
