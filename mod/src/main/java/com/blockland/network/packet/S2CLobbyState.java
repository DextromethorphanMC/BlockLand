package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Full lobby snapshot for one player.
 *
 * @param groups           mode groups; modes listed in a group are shown inside it instead of at the top level
 * @param queuedModeId     empty when the player is not queued
 * @param queuedSeconds    seconds the player has already been waiting
 * @param inGame           the player is currently in a match
 * @param openScreen       the client should open the lobby screen if nothing else is open
 * @param playtimeSeconds  the player's total online time across the network
 * @param priorityQueue    the player is placed at the front of matchmaking queues
 * @param party            the player's party and invitations
 */
public record S2CLobbyState(List<LobbyModeEntry> modes, List<LobbyGroupEntry> groups, String queuedModeId, int queuedSeconds,
                            boolean inGame, boolean openScreen, long playtimeSeconds, boolean priorityQueue,
                            PartyPackets.PartyInfo party) {

    public static void encode(S2CLobbyState msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.modes.size());
        for (LobbyModeEntry mode : msg.modes) mode.encode(buf);
        buf.writeVarInt(msg.groups.size());
        for (LobbyGroupEntry group : msg.groups) group.encode(buf);
        buf.writeUtf(msg.queuedModeId, 64);
        buf.writeVarInt(msg.queuedSeconds);
        buf.writeBoolean(msg.inGame);
        buf.writeBoolean(msg.openScreen);
        buf.writeVarLong(msg.playtimeSeconds);
        buf.writeBoolean(msg.priorityQueue);
        msg.party.encode(buf);
    }

    public static S2CLobbyState decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<LobbyModeEntry> modes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) modes.add(LobbyModeEntry.decode(buf));
        int groupCount = buf.readVarInt();
        List<LobbyGroupEntry> groups = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) groups.add(LobbyGroupEntry.decode(buf));
        return new S2CLobbyState(modes, groups, buf.readUtf(64), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarLong(), buf.readBoolean(), PartyPackets.PartyInfo.decode(buf));
    }
}
