package com.blockland.network;

import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.S2CKitData;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.network.packet.S2CMatchResult;
import com.blockland.network.packet.ShopPackets;

/** Client-side handler for server pushes. Implemented by the client module. */
public interface ClientPacketListener {
    ClientPacketListener NOOP = new ClientPacketListener() {
        @Override public void onLobbyState(S2CLobbyState state) { }
        @Override public void onMatchFound(S2CMatchFound match) { }
        @Override public void onKitData(S2CKitData kit) { }
        @Override public void onShopData(ShopPackets.S2CData data) { }
        @Override public void onMedia(MediaPackets.S2CImage image) { }
        @Override public void onPurchaseResult(ShopPackets.S2CPurchaseResult result) { }
        @Override public void onMatchResult(S2CMatchResult result) { }
        @Override public void onNotice(LobbyPackets.S2CNotice notice) { }
        @Override public void onAnnouncements(LobbyPackets.S2CAnnouncements announcements) { }
    };

    void onLobbyState(S2CLobbyState state);

    void onMatchFound(S2CMatchFound match);

    void onKitData(S2CKitData kit);

    void onShopData(ShopPackets.S2CData data);

    void onMedia(MediaPackets.S2CImage image);

    void onPurchaseResult(ShopPackets.S2CPurchaseResult result);

    /** A match ended: show the results panel. */
    void onMatchResult(S2CMatchResult result);

    /** A server message while there is no chat (the lobby). */
    void onNotice(LobbyPackets.S2CNotice notice);

    void onAnnouncements(LobbyPackets.S2CAnnouncements announcements);
}
