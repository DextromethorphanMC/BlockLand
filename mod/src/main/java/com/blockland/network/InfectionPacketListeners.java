package com.blockland.network;

import com.blockland.network.packet.InfectionPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for 枪战感染 packets, kept apart from the other listeners so the game type can be wired
 * (or left out) on its own. Implemented by the infection game's router (server) and the client modules.
 */
public final class InfectionPacketListeners {
    private InfectionPacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onSelectMonster(ServerPlayer player, String monsterId) { }
            @Override public void onUseSkill(ServerPlayer player) { }
        };

        void onSelectMonster(ServerPlayer player, String monsterId);

        void onUseSkill(ServerPlayer player);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onSelect(InfectionPackets.S2CSelect select) { }
            @Override public void onRole(InfectionPackets.S2CRole role) { }
            @Override public void onSkill(InfectionPackets.S2CSkill skill) { }
        };

        void onSelect(InfectionPackets.S2CSelect select);

        void onRole(InfectionPackets.S2CRole role);

        void onSkill(InfectionPackets.S2CSkill skill);
    }
}
