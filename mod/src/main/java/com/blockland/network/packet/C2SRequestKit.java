package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Client opens the loadout editor for a mode. */
public record C2SRequestKit(String modeId) {
    public static void encode(C2SRequestKit msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modeId, 64);
    }

    public static C2SRequestKit decode(FriendlyByteBuf buf) {
        return new C2SRequestKit(buf.readUtf(64));
    }
}
