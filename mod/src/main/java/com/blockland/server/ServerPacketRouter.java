package com.blockland.server;

import com.blockland.network.ServerPacketListener;
import com.blockland.network.packet.PartyPackets;
import com.blockland.server.lobby.LobbyService;
import com.blockland.server.media.MediaRelay;
import com.blockland.server.shop.ShopRelay;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

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
    public void onLobbyRequest(ServerPlayer player) {
        lobby.sendState(player);
    }

    @Override
    public void onJoinQueue(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots) {
        lobby.joinQueue(player, modeId, kitSignature, kitSlots);
    }

    @Override
    public void onLeaveQueue(ServerPlayer player) {
        lobby.leaveQueue(player);
    }

    @Override
    public void onRequestKit(ServerPlayer player, String modeId) {
        lobby.sendKit(player, modeId);
    }

    @Override
    public void onShopRequest(ServerPlayer player) {
        shop.sendCatalogue(player);
    }

    @Override
    public void onMediaRequest(ServerPlayer player, String namespace, String id, String version) {
        media.send(player, namespace, id, version);
    }

    @Override
    public void onPurchase(ServerPlayer player, String productId) {
        shop.purchase(player, productId);
    }

    @Override
    public void onSubmitKit(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots) {
        lobby.submitKit(player, modeId, kitSignature, kitSlots);
    }

    @Override
    public void onPartyAction(ServerPlayer player, PartyPackets.Action action, String argument) {
        lobby.partyAction(player, action, argument);
    }
}
