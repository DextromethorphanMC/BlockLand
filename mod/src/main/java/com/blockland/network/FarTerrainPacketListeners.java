package com.blockland.network;

import com.blockland.network.packet.FarChunkPackets;
import com.blockland.network.packet.FarTerrainPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for far terrain packets (rendering spec §22), kept apart from the other listeners so the server's tile service
 * and the client's far terrain can be wired (or left out) on their own.
 */
public final class FarTerrainPacketListeners {
    private FarTerrainPacketListeners() {
    }

    public interface Server {
        Server NOOP = new Server() {
            @Override
            public void onRequest(ServerPlayer player, FarTerrainPackets.C2SRequest request) {
            }

            @Override
            public void onColumns(ServerPlayer player, FarChunkPackets.C2SRequest request) {
            }
        };

        void onRequest(ServerPlayer player, FarTerrainPackets.C2SRequest request);

        /** Real far chunks (rendering spec §23). */
        void onColumns(ServerPlayer player, FarChunkPackets.C2SRequest request);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override
            public void onHello(FarTerrainPackets.S2CHello hello) {
            }

            @Override
            public void onTiles(FarTerrainPackets.S2CTiles tiles) {
            }

            @Override
            public void onColumns(FarChunkPackets.S2CColumns columns) {
            }
        };

        void onHello(FarTerrainPackets.S2CHello hello);

        void onTiles(FarTerrainPackets.S2CTiles tiles);

        /** Real far chunks (rendering spec §23). */
        void onColumns(FarChunkPackets.S2CColumns columns);
    }
}
