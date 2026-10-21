package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

public record C2SLeaveQueue() {
    public static final C2SLeaveQueue INSTANCE = new C2SLeaveQueue();

    public static void encode(C2SLeaveQueue msg, FriendlyByteBuf buf) {
    }

    public static C2SLeaveQueue decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
