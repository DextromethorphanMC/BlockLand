package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Join a mode's queue. Carries the player's locally saved kit arrangement for that mode
 * ({@code kitSlots} empty = default layout); the server validates it against its preset.
 */
public record C2SJoinQueue(String modeId, String kitSignature, List<String> kitSlots) {
    private static final int MAX_SLOTS = 64;

    public static void encode(C2SJoinQueue msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modeId, 64);
        buf.writeUtf(msg.kitSignature, 32);
        buf.writeVarInt(msg.kitSlots.size());
        for (String slot : msg.kitSlots) buf.writeUtf(slot, 16);
    }

    public static C2SJoinQueue decode(FriendlyByteBuf buf) {
        String modeId = buf.readUtf(64);
        String signature = buf.readUtf(32);
        int size = Math.min(buf.readVarInt(), MAX_SLOTS);
        List<String> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) slots.add(buf.readUtf(16));
        return new C2SJoinQueue(modeId, signature, slots);
    }
}
