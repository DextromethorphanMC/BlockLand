package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Display-only description of a game mode, independent of the game type implementation.
 *
 * @param timeLimitSeconds  0 when the mode has no time limit
 * @param unavailableReason empty when the mode can be queued
 * @param hasKit            players start with a kit they can arrange in the loadout editor
 * @param coverVersion      empty when the mode has no cover
 * @param availableUntil    epoch millis when a limited-time mode closes, 0 for permanent modes
 * @param maps              the maps the mode can be played on right now (players may ask for one when queueing)
 * @param dropIn            no queue: joining enters the running world at once ({@code playing} = who is in it)
 * @param ranked            排位: matches count for the ranked ladder and are matched by score
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
        long availableUntil,
        List<MapOption> maps,
        boolean dropIn,
        boolean ranked
) {
    /** @param coverVersion empty when the map has no cover image */
    public record MapOption(String id, String name, String coverVersion) {
    }

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
        buf.writeVarInt(maps.size());
        for (MapOption map : maps) {
            buf.writeUtf(map.id, 64);
            buf.writeUtf(map.name, 128);
            buf.writeUtf(map.coverVersion, 64);
        }
        buf.writeBoolean(dropIn);
        buf.writeBoolean(ranked);
    }

    public static LobbyModeEntry decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64), title = buf.readUtf(), typeName = buf.readUtf(), description = buf.readUtf(), format = buf.readUtf();
        int required = buf.readVarInt(), timeLimit = buf.readVarInt(), queued = buf.readVarInt(), playing = buf.readVarInt();
        int color = buf.readInt();
        String reason = buf.readUtf();
        boolean hasKit = buf.readBoolean();
        String cover = buf.readUtf(64);
        long until = buf.readLong();
        int mapCount = Math.min(buf.readVarInt(), 256);
        List<MapOption> maps = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) maps.add(new MapOption(buf.readUtf(64), buf.readUtf(128), buf.readUtf(64)));
        boolean dropIn = buf.readBoolean();
        boolean ranked = buf.readBoolean();
        return new LobbyModeEntry(id, title, typeName, description, format, required, timeLimit, queued, playing, color, reason,
                hasKit, cover, until, maps, dropIn, ranked);
    }
}
