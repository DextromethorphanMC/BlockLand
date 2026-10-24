package com.blockland.network;

import com.blockland.network.packet.ProfilePackets;

import java.util.UUID;

/**
 * Handlers for the lobby's 库存 and 个人主页 packets, kept apart from the other listeners. Requests arrive by player id
 * (they are sent from the lobby, where there is no {@code ServerPlayer}).
 */
public final class ProfilePacketListeners {
    private ProfilePacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onRequest(UUID player, ProfilePackets.Kind kind, String modeId) { }
            @Override public void onEquip(UUID player, String slot, String key) { }
        };

        /** {@code modeId}: the ladder's mode for a leaderboard request, else "". */
        void onRequest(UUID player, ProfilePackets.Kind kind, String modeId);

        void onEquip(UUID player, String slot, String key);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onInventory(ProfilePackets.S2CInventory inventory) { }
            @Override public void onProfile(ProfilePackets.S2CProfile profile) { }
            @Override public void onLeaderboard(ProfilePackets.S2CLeaderboard leaderboard) { }
        };

        void onInventory(ProfilePackets.S2CInventory inventory);

        void onProfile(ProfilePackets.S2CProfile profile);

        void onLeaderboard(ProfilePackets.S2CLeaderboard leaderboard);
    }
}
