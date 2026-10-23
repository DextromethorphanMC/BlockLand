package com.blockland.network;

import com.blockland.network.packet.CraftPackets;

import java.util.UUID;

/**
 * Handlers for 工匠 (craft) packets, kept apart so the craft feature can be wired on its own. Senders are player ids,
 * not entities: builds are edited in the lobby too, where players are not in a world.
 */
public final class CraftPacketListeners {
    private CraftPacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override public void onRequest(UUID player) { }
            @Override public void onSave(UUID player, CraftPackets.C2SSave save) { }
        };

        void onRequest(UUID player);

        void onSave(UUID player, CraftPackets.C2SSave save);
    }

    public interface Client {
        Client NOOP = builds -> { };

        void onBuilds(CraftPackets.S2CBuilds builds);
    }
}
