package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Movement messages: the slide request, slide states for third person and the rules the client may predict with. */
public final class MovementPackets {
    private MovementPackets() {
    }

    /**
     * The client started (or ended) a slide.
     *
     * @param yaw slide direction (Minecraft yaw) when starting
     */
    public record C2SSlide(boolean start, float yaw) {
        public static void encode(C2SSlide msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.start);
            buf.writeFloat(msg.yaw);
        }

        public static C2SSlide decode(FriendlyByteBuf buf) {
            return new C2SSlide(buf.readBoolean(), buf.readFloat());
        }
    }

    /**
     * A player started or stopped sliding: to everyone tracking them (third-person pose, hit prediction), and to the
     * slider itself when the server refused or ended its slide.
     */
    public record S2CSlide(int entityId, boolean sliding, float yaw) {
        public static void encode(S2CSlide msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeBoolean(msg.sliding);
            buf.writeFloat(msg.yaw);
        }

        public static S2CSlide decode(FriendlyByteBuf buf) {
            return new S2CSlide(buf.readVarInt(), buf.readBoolean(), buf.readFloat());
        }
    }

    /** What the player may do right now (the game mode's rules); the client only predicts with it. */
    public record S2CRules(boolean slideAllowed) {
        public static void encode(S2CRules msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.slideAllowed);
        }

        public static S2CRules decode(FriendlyByteBuf buf) {
            return new S2CRules(buf.readBoolean());
        }
    }
}
