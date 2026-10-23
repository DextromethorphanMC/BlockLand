package com.blockland.network;

import com.blockland.network.packet.CombatPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for kill feedback and the match board, kept apart from the weapon listeners: every weapon's kills end up
 * here without the feedback knowing the weapons. Implemented by the server kill tracker and the client HUD module.
 */
public final class CombatPacketListeners {
    private CombatPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, open) -> { };

        void onBoard(ServerPlayer player, boolean open);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onKillNotice(CombatPackets.S2CKillNotice notice) { }
            @Override public void onKillFeed(CombatPackets.S2CKillFeed feed) { }
            @Override public void onBoard(CombatPackets.S2CBoard board) { }
        };

        void onKillNotice(CombatPackets.S2CKillNotice notice);

        void onKillFeed(CombatPackets.S2CKillFeed feed);

        void onBoard(CombatPackets.S2CBoard board);
    }
}
