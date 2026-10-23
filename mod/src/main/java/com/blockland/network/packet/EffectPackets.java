package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Messages of equipment effects that are about one player's own senses (a flashbang's blinding). */
public final class EffectPackets {
    private EffectPackets() {
    }

    /**
     * A flashbang went off where the player could see it.
     *
     * @param strength 0..1 (distance, facing, line of sight)
     * @param seconds  how long the white-out lasts until the view is clear again
     * @param deafen   seconds of ringing and muffled hearing
     */
    public record S2CFlash(float strength, float seconds, float deafen, double x, double y, double z) {
        public static void encode(S2CFlash msg, FriendlyByteBuf buf) {
            buf.writeFloat(msg.strength);
            buf.writeFloat(msg.seconds);
            buf.writeFloat(msg.deafen);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
        }

        public static S2CFlash decode(FriendlyByteBuf buf) {
            return new S2CFlash(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
    }
}
