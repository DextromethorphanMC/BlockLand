package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Tells the client its queue popped and a match is being prepared. */
public record S2CMatchFound(String modeTitle) {
    public static void encode(S2CMatchFound msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modeTitle);
    }

    public static S2CMatchFound decode(FriendlyByteBuf buf) {
        return new S2CMatchFound(buf.readUtf());
    }
}
