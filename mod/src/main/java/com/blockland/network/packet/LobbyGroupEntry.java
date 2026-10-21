package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A mode group as the lobby shows it.
 *
 * @param coverVersion empty when the group has no cover
 * @param modeIds      ids of {@link LobbyModeEntry modes} in this group, in display order
 */
public record LobbyGroupEntry(String id, String title, String description, String coverVersion, List<String> modeIds) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id, 64);
        buf.writeUtf(title);
        buf.writeUtf(description);
        buf.writeUtf(coverVersion, 64);
        buf.writeVarInt(modeIds.size());
        for (String modeId : modeIds) buf.writeUtf(modeId, 64);
    }

    public static LobbyGroupEntry decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        String title = buf.readUtf();
        String description = buf.readUtf();
        String cover = buf.readUtf(64);
        int size = buf.readVarInt();
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(buf.readUtf(64));
        return new LobbyGroupEntry(id, title, description, cover, ids);
    }
}
