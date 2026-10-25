package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Body armour messages (asset spec §50): putting a plate into the worn carrier, and what a hit did to the armour. */
public final class ArmorPackets {
    private ArmorPackets() {
    }

    /** What became of a plate insertion. */
    public enum Result {
        /** Going in: it is in after the given seconds unless the player lets go of it. */
        STARTED,
        DONE,
        NO_VEST,
        FULL,
        /** The plate left the hand, the carrier came off or the player died. */
        CANCELLED
    }

    /** The client starts putting the plate held in hotbar slot {@code slot} into its carrier (a left-click). */
    public record C2SInsert(int slot) {
        public static void encode(C2SInsert msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
        }

        public static C2SInsert decode(FriendlyByteBuf buf) {
            return new C2SInsert(buf.readVarInt());
        }
    }

    /** The server's word on an insertion: {@code plates} now in the carrier, {@code seconds} it takes (STARTED). */
    public record S2CInsert(Result result, int plates, float seconds) {
        public static void encode(S2CInsert msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.result);
            buf.writeVarInt(msg.plates);
            buf.writeFloat(msg.seconds);
        }

        public static S2CInsert decode(FriendlyByteBuf buf) {
            return new S2CInsert(buf.readEnum(Result.class), buf.readVarInt(), buf.readFloat());
        }
    }

    /** To the wearer: a hit was taken on the armour — {@code soaked} points, {@code broken} plates broke, {@code left} remain. */
    public record S2CHit(float soaked, int broken, int left) {
        public static void encode(S2CHit msg, FriendlyByteBuf buf) {
            buf.writeFloat(msg.soaked);
            buf.writeVarInt(msg.broken);
            buf.writeVarInt(msg.left);
        }

        public static S2CHit decode(FriendlyByteBuf buf) {
            return new S2CHit(buf.readFloat(), buf.readVarInt(), buf.readVarInt());
        }
    }
}
