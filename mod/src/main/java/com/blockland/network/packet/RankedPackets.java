package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 排位 messages, server to client in any phase (the lobby and the world): the player's own standing, and what their
 * last ranked match did to it. Registry-free: tiers travel as their {@code RankTier} id.
 */
public final class RankedPackets {
    private RankedPackets() {
    }

    /**
     * The player's standing on the ranked ladder.
     *
     * @param position place on the server's ladder (1 = best), 0 = not placed yet
     */
    public record S2CRank(int score, String tier, int position, int matches, int wins) {
        public static void encode(S2CRank msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.score);
            buf.writeUtf(msg.tier, 16);
            buf.writeVarInt(msg.position);
            buf.writeVarInt(msg.matches);
            buf.writeVarInt(msg.wins);
        }

        public static S2CRank decode(FriendlyByteBuf buf) {
            return new S2CRank(buf.readVarInt(), buf.readUtf(16), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * A ranked match changed the player's score.
     *
     * @param modeTitle the mode it was played in (as the backend calls it)
     * @param left      the player left the match before its end (counted as a defeat)
     */
    public record S2CRankChange(String modeTitle, int before, int after, String tierBefore, String tier, int position, boolean left) {
        public int delta() {
            return after - before;
        }

        public static void encode(S2CRankChange msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.modeTitle, 128);
            buf.writeVarInt(msg.before);
            buf.writeVarInt(msg.after);
            buf.writeUtf(msg.tierBefore, 16);
            buf.writeUtf(msg.tier, 16);
            buf.writeVarInt(msg.position);
            buf.writeBoolean(msg.left);
        }

        public static S2CRankChange decode(FriendlyByteBuf buf) {
            return new S2CRankChange(buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readUtf(16), buf.readUtf(16),
                    buf.readVarInt(), buf.readBoolean());
        }
    }
}
