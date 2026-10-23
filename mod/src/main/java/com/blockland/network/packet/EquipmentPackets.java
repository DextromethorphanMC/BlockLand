package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Equipment messages: readying, throwing or using the held item, and the thrower's hit confirmation. */
public final class EquipmentPackets {
    private EquipmentPackets() {
    }

    /**
     * PRESS readies the item in the selected slot (a grenade's pin comes out, the molotov is lit; a knife is thrown and
     * an injector used at once), RELEASE lets a readied item go, CANCEL puts it away (switching slots: a live grenade
     * drops at the feet).
     */
    public enum Action { PRESS, RELEASE, CANCEL }

    /** @param lob the right button (underhand toss) instead of the left (overhand throw) */
    public record C2SAction(int slot, Action action, boolean lob) {
        public static void encode(C2SAction msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.slot);
            buf.writeEnum(msg.action);
            buf.writeBoolean(msg.lob);
        }

        public static C2SAction decode(FriendlyByteBuf buf) {
            return new C2SAction(buf.readVarInt(), buf.readEnum(Action.class), buf.readBoolean());
        }
    }

    /** A thrown knife struck someone: the thrower's hit marker. */
    public record S2CHit(boolean head, boolean kill, float damage) {
        public static void encode(S2CHit msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.head);
            buf.writeBoolean(msg.kill);
            buf.writeFloat(msg.damage);
        }

        public static S2CHit decode(FriendlyByteBuf buf) {
            return new S2CHit(buf.readBoolean(), buf.readBoolean(), buf.readFloat());
        }
    }
}
