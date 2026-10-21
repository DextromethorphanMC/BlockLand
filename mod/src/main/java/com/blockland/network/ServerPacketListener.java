package com.blockland.network;

import com.blockland.network.packet.PartyPackets;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Server-side handler for client requests. Implemented by the server module. */
public interface ServerPacketListener {
    ServerPacketListener NOOP = new ServerPacketListener() {
        @Override public void onLobbyRequest(ServerPlayer player) { }
        @Override public void onJoinQueue(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots) { }
        @Override public void onLeaveQueue(ServerPlayer player) { }
        @Override public void onRequestKit(ServerPlayer player, String modeId) { }
        @Override public void onShopRequest(ServerPlayer player) { }
        @Override public void onMediaRequest(ServerPlayer player, String namespace, String id, String version) { }
        @Override public void onPurchase(ServerPlayer player, String productId) { }
        @Override public void onSubmitKit(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots) { }
        @Override public void onPartyAction(ServerPlayer player, PartyPackets.Action action, String argument) { }
    };

    void onLobbyRequest(ServerPlayer player);

    /** @param kitSlots the player's saved arrangement of the mode's kit (empty = default) */
    void onJoinQueue(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots);

    void onLeaveQueue(ServerPlayer player);

    void onRequestKit(ServerPlayer player, String modeId);

    void onShopRequest(ServerPlayer player);

    void onMediaRequest(ServerPlayer player, String namespace, String id, String version);

    void onPurchase(ServerPlayer player, String productId);

    /** A party member's kit arrangement for the mode their party is queued for. */
    void onSubmitKit(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots);

    void onPartyAction(ServerPlayer player, PartyPackets.Action action, String argument);
}
