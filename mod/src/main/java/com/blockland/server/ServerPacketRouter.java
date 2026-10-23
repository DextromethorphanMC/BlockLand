package com.blockland.server;

import com.blockland.network.ServerPacketListener;
import com.blockland.network.packet.PartyPackets;
import com.blockland.server.lobby.LobbyService;
import com.blockland.server.media.MediaRelay;
import com.blockland.server.shop.ShopRelay;

import java.util.List;
import java.util.UUID;

/** Dispatches client requests to the service that owns them. */
final class ServerPacketRouter implements ServerPacketListener {
    private final LobbyService lobby;
    private final ShopRelay shop;
    private final MediaRelay media;

    ServerPacketRouter(LobbyService lobby, ShopRelay shop, MediaRelay media) {
        this.lobby = lobby;
        this.shop = shop;
        this.media = media;
    }

    @Override
    public void onLobbyRequest(UUID player) {
        lobby.sendState(player);
    }

    @Override
    public void onJoinQueue(UUID player, String modeId, String mapId, String kitSignature, List<String> kitSlots) {
        lobby.joinQueue(player, modeId, mapId, kitSignature, kitSlots);
    }

    @Override
    public void onLeaveQueue(UUID player) {
        lobby.leaveQueue(player);
    }

    @Override
    public void onRequestKit(UUID player, String modeId) {
        lobby.sendKit(player, modeId);
    }

    @Override
    public void onShopRequest(UUID player) {
        shop.sendCatalogue(player);
    }

    @Override
    public void onMediaRequest(UUID player, String namespace, String id, String version) {
        media.send(player, namespace, id, version);
    }

    @Override
    public void onPurchase(UUID player, String productId) {
        shop.purchase(player, productId);
    }

    @Override
    public void onSubmitKit(UUID player, String modeId, String kitSignature, List<String> kitSlots) {
        lobby.submitKit(player, modeId, kitSignature, kitSlots);
    }

    @Override
    public void onPartyAction(UUID player, PartyPackets.Action action, String argument) {
        lobby.partyAction(player, action, argument);
    }

    @Override
    public void onPostMatch(UUID player, boolean playAgain, String kitSignature, List<String> kitSlots) {
        lobby.postMatch(player, playAgain, kitSignature, kitSlots);
    }

    @Override
    public void onSettings(UUID player, boolean allowPartyInvites) {
        lobby.setAllowInvites(player, allowPartyInvites);
    }

    @Override
    public void onReadAnnouncements(UUID player, List<String> ids) {
        lobby.markRead(player, ids);
    }

    @Override
    public void onEnterWorld(UUID player) {
        lobby.enterWorld(player);
    }
}
