package com.blockland.network;

import com.blockland.network.packet.PartyPackets;

import java.util.List;
import java.util.UUID;

/**
 * Server-side handler for client requests. Implemented by the server module. Players are addressed by id: lobby
 * requests arrive in the configuration phase, where there is no {@code ServerPlayer}.
 */
public interface ServerPacketListener {
    ServerPacketListener NOOP = new ServerPacketListener() {
        @Override public void onLobbyRequest(UUID player) { }
        @Override public void onJoinQueue(UUID player, String modeId, String mapId, String kitSignature, List<String> kitSlots) { }
        @Override public void onLeaveQueue(UUID player) { }
        @Override public void onRequestKit(UUID player, String modeId) { }
        @Override public void onShopRequest(UUID player) { }
        @Override public void onMediaRequest(UUID player, String namespace, String id, String version) { }
        @Override public void onPurchase(UUID player, String productId) { }
        @Override public void onSubmitKit(UUID player, String modeId, String kitSignature, List<String> kitSlots) { }
        @Override public void onPartyAction(UUID player, PartyPackets.Action action, String argument) { }
        @Override public void onPostMatch(UUID player, boolean playAgain, String kitSignature, List<String> kitSlots) { }
        @Override public void onSettings(UUID player, boolean allowPartyInvites) { }
        @Override public void onReadAnnouncements(UUID player, List<String> ids) { }
        @Override public void onEnterWorld(UUID player) { }
    };

    void onLobbyRequest(UUID player);

    /**
     * @param mapId    one of the mode's maps, empty = any
     * @param kitSlots the player's saved arrangement of the mode's kit (empty = default)
     */
    void onJoinQueue(UUID player, String modeId, String mapId, String kitSignature, List<String> kitSlots);

    void onLeaveQueue(UUID player);

    void onRequestKit(UUID player, String modeId);

    void onShopRequest(UUID player);

    void onMediaRequest(UUID player, String namespace, String id, String version);

    void onPurchase(UUID player, String productId);

    /** A party member's kit arrangement for the mode their party is queued for. */
    void onSubmitKit(UUID player, String modeId, String kitSignature, List<String> kitSlots);

    void onPartyAction(UUID player, PartyPackets.Action action, String argument);

    /** A results panel button: back to the lobby now, or queue the finished match's mode again. */
    void onPostMatch(UUID player, boolean playAgain, String kitSignature, List<String> kitSlots);

    /** Server-side preferences from the settings page (privacy). */
    void onSettings(UUID player, boolean allowPartyInvites);

    /** The player marked announcements as read. */
    void onReadAnnouncements(UUID player, List<String> ids);

    /** An operator asks to leave the lobby for the world. */
    void onEnterWorld(UUID player);
}
