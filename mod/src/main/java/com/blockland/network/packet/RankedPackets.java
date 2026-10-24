package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 排位 messages, server to client in any phase (the lobby and the world): the player's own standing in every ranked mode
 * (each mode has a ladder of its own), and what their last ranked match did to it. Registry-free: tiers travel as their
 * {@code RankTier} id.
 */
public final class RankedPackets {
    private RankedPackets() {
    }

    /**
     * A standing on one mode's ladder.
     *
     * @param position place on the mode's ladder (1 = best), 0 = not placed yet
     */
    public record Standing(String modeId, int score, String tier, int position, int matches, int wins) {
        /** The same standing for another mode (the start standing of a mode not played yet). */
        public Standing in(String mode) {
            return new Standing(mode, score, tier, position, matches, wins);
        }

        static void write(FriendlyByteBuf buf, Standing s) {
            buf.writeUtf(s.modeId, 64);
            buf.writeVarInt(s.score);
            buf.writeUtf(s.tier, 16);
            buf.writeVarInt(s.position);
            buf.writeVarInt(s.matches);
            buf.writeVarInt(s.wins);
        }

        static Standing read(FriendlyByteBuf buf) {
            return new Standing(buf.readUtf(64), buf.readVarInt(), buf.readUtf(16), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * The player's standings: one per mode they played ranked, and the start standing of every other mode.
     *
     * @param fresh the standing in a mode not played yet ({@code modeId} empty)
     */
    public record S2CRanks(Standing fresh, List<Standing> modes) {
        public S2CRanks {
            modes = List.copyOf(modes);
        }

        public static void encode(S2CRanks msg, FriendlyByteBuf buf) {
            Standing.write(buf, msg.fresh);
            buf.writeVarInt(msg.modes.size());
            for (Standing s : msg.modes) Standing.write(buf, s);
        }

        public static S2CRanks decode(FriendlyByteBuf buf) {
            Standing fresh = Standing.read(buf);
            int count = Math.min(buf.readVarInt(), 256);
            List<Standing> modes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) modes.add(Standing.read(buf));
            return new S2CRanks(fresh, modes);
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
