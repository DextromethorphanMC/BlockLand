package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Parachute messages: the canopy toggle request and canopy states for everyone who sees the jumper. */
public final class ParachutePackets {
    private ParachutePackets() {
    }

    /** The client opened ({@code open}) or closed its canopy (predicted already). */
    public record C2SToggle(boolean open) {
        public static void encode(C2SToggle msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.open);
        }

        public static C2SToggle decode(FriendlyByteBuf buf) {
            return new C2SToggle(buf.readBoolean());
        }
    }

    /**
     * A player's canopy opened or closed: to everyone tracking them (the canopy above them), and to the jumper itself
     * when the server refused, closed or opened it (a game deploying it).
     */
    public record S2CState(int entityId, boolean open) {
        public static void encode(S2CState msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeBoolean(msg.open);
        }

        public static S2CState decode(FriendlyByteBuf buf) {
            return new S2CState(buf.readVarInt(), buf.readBoolean());
        }
    }
}
