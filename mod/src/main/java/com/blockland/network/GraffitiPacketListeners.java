package com.blockland.network;

import com.blockland.network.packet.GraffitiPackets;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers for wall graffiti packets, kept apart from the other listeners. Implemented by the server's graffiti service
 * and the client graffiti module.
 */
public final class GraffitiPacketListeners {
    private GraffitiPacketListeners() {
    }

    public interface Server {
        Server NOOP = (player, slot) -> { };

        void onSpray(ServerPlayer player, int slot);
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onPlace(GraffitiPackets.S2CPlace place) { }
            @Override public void onRemove(GraffitiPackets.S2CRemove remove) { }
            @Override public void onSync(GraffitiPackets.S2CSync sync) { }
            @Override public void onSlots(GraffitiPackets.S2CSlots slots) { }
        };

        void onPlace(GraffitiPackets.S2CPlace place);

        void onRemove(GraffitiPackets.S2CRemove remove);

        void onSync(GraffitiPackets.S2CSync sync);

        void onSlots(GraffitiPackets.S2CSlots slots);
    }
}
