package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tells the client its queue popped and a match is being prepared, with what its loading screen shows: the mode, the
 * map (its cover comes through the media relay), the player's own side with everyone's name card and rank, and how many
 * play against them. Sent in any phase (the player usually still waits in the lobby); registry-free.
 *
 * @param accent    ARGB of the game type (lobby colour)
 * @param mapId     empty when the mode has no map (an open world)
 * @param dropIn    joining a running drop-in mode (an open world) rather than a match that starts now
 * @param players   everyone in the match (the player included)
 * @param opponents players not on the player's side
 * @param ranked    the mode is ranked (scores are shown)
 * @param team      the player's side, the player first
 */
public record S2CMatchFound(String modeId, String modeTitle, String typeName, int accent, String mapId, String mapName,
                            String coverVersion, boolean dropIn, int players, int opponents, boolean ranked, List<Member> team) {

    /**
     * @param card id of the name card (without {@code card:}; empty = default)
     * @param tier {@link com.blockland.common.RankTier} id
     * @param placed has a place on the ranked ladder (played ranked)
     */
    public record Member(UUID id, String name, String card, String tier, int score, boolean placed) {
    }

    public S2CMatchFound {
        team = List.copyOf(team);
    }

    public static void encode(S2CMatchFound msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modeId, 64);
        buf.writeUtf(msg.modeTitle);
        buf.writeUtf(msg.typeName, 64);
        buf.writeInt(msg.accent);
        buf.writeUtf(msg.mapId, 64);
        buf.writeUtf(msg.mapName, 128);
        buf.writeUtf(msg.coverVersion, 64);
        buf.writeBoolean(msg.dropIn);
        buf.writeVarInt(msg.players);
        buf.writeVarInt(msg.opponents);
        buf.writeBoolean(msg.ranked);
        buf.writeVarInt(msg.team.size());
        for (Member m : msg.team) {
            buf.writeUUID(m.id);
            buf.writeUtf(m.name, 32);
            buf.writeUtf(m.card, 64);
            buf.writeUtf(m.tier, 16);
            buf.writeVarInt(Math.max(0, m.score));
            buf.writeBoolean(m.placed);
        }
    }

    public static S2CMatchFound decode(FriendlyByteBuf buf) {
        String modeId = buf.readUtf(64);
        String title = buf.readUtf();
        String typeName = buf.readUtf(64);
        int accent = buf.readInt();
        String mapId = buf.readUtf(64);
        String mapName = buf.readUtf(128);
        String cover = buf.readUtf(64);
        boolean dropIn = buf.readBoolean();
        int players = buf.readVarInt();
        int opponents = buf.readVarInt();
        boolean ranked = buf.readBoolean();
        int count = Math.min(64, buf.readVarInt());
        List<Member> team = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            team.add(new Member(buf.readUUID(), buf.readUtf(32), buf.readUtf(64), buf.readUtf(16), buf.readVarInt(), buf.readBoolean()));
        }
        return new S2CMatchFound(modeId, title, typeName, accent, mapId, mapName, cover, dropIn, players, opponents, ranked, team);
    }
}
