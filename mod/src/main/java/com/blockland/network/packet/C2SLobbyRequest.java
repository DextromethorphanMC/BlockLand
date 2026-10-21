package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** Client asks for the current lobby state (and to open the lobby screen). */
public record C2SLobbyRequest() {
    public static final C2SLobbyRequest INSTANCE = new C2SLobbyRequest();

    public static void encode(C2SLobbyRequest msg, FriendlyByteBuf buf) {
    }

    public static C2SLobbyRequest decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
