package com.blockland.network;

import com.blockland.network.packet.BattleRoyalePackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for 大逃杀 packets, kept apart from the other listeners so the game type can be wired (or left out) on its
 * own. Implemented by the battle royale game's router (server) and the client module.
 */
public final class BattleRoyalePacketListeners {
    private BattleRoyalePacketListeners() {
    }

    public interface Server {
        Server NOOP = player -> { };

        void onJump(ServerPlayer player);
    }

    public interface Client {
        Client NOOP = state -> { };

        void onState(BattleRoyalePackets.S2CState state);
    }
}
