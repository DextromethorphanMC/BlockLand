package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * What a spectator watching a player through their eyes needs to animate that player's first-person weapon beyond what
 * every tracker already gets (shots, aiming, the held item itself): the hand's actions. Sent only to the players whose
 * camera is that player.
 */
public final class ViewmodelPackets {
    private ViewmodelPackets() {
    }

    /**
     * RELOAD: {@code a} 0 = with rounds left, 1 = from empty, 2 = shell by shell. MELEE: {@code a} 1 = heavy,
     * {@code b} the light combo step. EQUIPMENT: {@code a} 0 = pressed (pull / throw / use), 1 = released (throw),
     * 2 = put away; {@code b} 1 = an underhand lob.
     */
    public enum Kind { RELOAD, MELEE, EQUIPMENT }

    public record S2CAction(int playerId, Kind kind, int a, int b) {
        public static void encode(S2CAction msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.playerId);
            buf.writeEnum(msg.kind);
            buf.writeVarInt(msg.a);
            buf.writeVarInt(msg.b);
        }

        public static S2CAction decode(FriendlyByteBuf buf) {
            return new S2CAction(buf.readVarInt(), buf.readEnum(Kind.class), buf.readVarInt(), buf.readVarInt());
        }
    }
}
