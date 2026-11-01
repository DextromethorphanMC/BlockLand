package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Where a player's hurt came from (UI spec §3.5): the server's word for the HUD's damage direction arrows. */
public final class DamagePackets {
    private DamagePackets() {
    }

    /**
     * To a player in the world, once per hurt that really took something off them: where it came from (world
     * coordinates: the attacker's eyes, or the blast) and the attacker to follow while it moves ({@code attacker} its
     * entity id, 0 to stay at the point), what it took off their health (absorption hearts included) and what the body
     * armour's plates soaked of it.
     */
    public record S2CDamage(int attacker, double x, double y, double z, float health, float plates) {
        public static void encode(S2CDamage msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.attacker);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.health);
            buf.writeFloat(msg.plates);
        }

        public static S2CDamage decode(FriendlyByteBuf buf) {
            return new S2CDamage(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat());
        }
    }
}
