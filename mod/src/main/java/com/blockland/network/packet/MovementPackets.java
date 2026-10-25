package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Movement messages (spec §47): the client's action requests (slide, prone, dive: {@code MovementAction} by index),
 * the action states for third person and hit prediction, and the rules the client may predict with.
 */
public final class MovementPackets {
    private MovementPackets() {
    }

    /**
     * The client started (or ended) an action.
     *
     * @param action {@code MovementAction} index
     * @param yaw    the action's direction (Minecraft yaw) when starting
     */
    public record C2SAction(int action, boolean start, float yaw) {
        public static void encode(C2SAction msg, FriendlyByteBuf buf) {
            buf.writeByte(msg.action);
            buf.writeBoolean(msg.start);
            buf.writeFloat(msg.yaw);
        }

        public static C2SAction decode(FriendlyByteBuf buf) {
            return new C2SAction(buf.readByte(), buf.readBoolean(), buf.readFloat());
        }
    }

    /**
     * A player's action changed: to everyone tracking them (third-person pose, hit prediction), and to the player itself
     * when the server refused, ended or changed its action (a dive landing prone).
     *
     * @param action {@code MovementAction} index, -1 = none
     */
    public record S2CAction(int entityId, int action, float yaw) {
        public static void encode(S2CAction msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeByte(msg.action);
            buf.writeFloat(msg.yaw);
        }

        public static S2CAction decode(FriendlyByteBuf buf) {
            return new S2CAction(buf.readVarInt(), buf.readByte(), buf.readFloat());
        }
    }

    /**
     * What the player may do right now (the game mode's rules); the client only predicts with it.
     *
     * @param allowed bit {@code MovementAction} index set = that action is allowed
     */
    public record S2CRules(int allowed) {
        public static void encode(S2CRules msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.allowed);
        }

        public static S2CRules decode(FriendlyByteBuf buf) {
            return new S2CRules(buf.readVarInt());
        }

        public boolean allows(int action) {
            return action >= 0 && (allowed >> action & 1) != 0;
        }
    }
}
