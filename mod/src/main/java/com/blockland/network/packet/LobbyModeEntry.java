package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Display-only description of a game mode, independent of the game type implementation.
 *
 * @param timeLimitSeconds  0 when the mode has no time limit
 * @param unavailableReason empty when the mode can be queued
 * @param hasKit            players start with a kit they can arrange in the loadout editor
 * @param coverVersion      empty when the mode has no cover
 * @param availableUntil    epoch millis when a limited-time mode closes, 0 for permanent modes
 */
public record LobbyModeEntry(
        String id,
        String title,
        String typeName,
        String description,
        String formatLabel,
        int requiredPlayers,
        int timeLimitSeconds,
        int queued,
        int playing,
        int accentColor,
        String unavailableReason,
        boolean hasKit,
        String coverVersion,
        long availableUntil
) {
    public boolean available() {
        return unavailableReason.isEmpty();
    }

    public boolean isLimited() {
        return availableUntil > 0;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id, 64);
        buf.writeUtf(title);
        buf.writeUtf(typeName);
        buf.writeUtf(description);
        buf.writeUtf(formatLabel);
        buf.writeVarInt(requiredPlayers);
        buf.writeVarInt(timeLimitSeconds);
        buf.writeVarInt(queued);
        buf.writeVarInt(playing);
        buf.writeInt(accentColor);
        buf.writeUtf(unavailableReason);
        buf.writeBoolean(hasKit);
        buf.writeUtf(coverVersion, 64);
        buf.writeLong(availableUntil);
    }

    public static LobbyModeEntry decode(FriendlyByteBuf buf) {
        return new LobbyModeEntry(buf.readUtf(64), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readUtf(), buf.readBoolean(), buf.readUtf(64), buf.readLong());
    }
}
