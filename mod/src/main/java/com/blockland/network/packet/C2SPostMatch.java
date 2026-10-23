package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A button on the results panel: back to the lobby now, or play the same mode again. The server takes the mode
 * from the match the player is finishing (never from the client) and only accepts this while that match is ending.
 *
 * @param kitSlots the player's saved arrangement of that mode's kit, for queueing again (empty = default)
 */
public record C2SPostMatch(boolean playAgain, String kitSignature, List<String> kitSlots) {
    private static final int MAX_SLOTS = 64;

    public static void encode(C2SPostMatch msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.playAgain);
        buf.writeUtf(msg.kitSignature, 32);
        int size = Math.min(MAX_SLOTS, msg.kitSlots.size());
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) buf.writeUtf(msg.kitSlots.get(i), 16);
    }

    public static C2SPostMatch decode(FriendlyByteBuf buf) {
        boolean playAgain = buf.readBoolean();
        String signature = buf.readUtf(32);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_SLOTS) throw new IllegalArgumentException("too many kit slots: " + size);
        List<String> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) slots.add(buf.readUtf(16));
        return new C2SPostMatch(playAgain, signature, slots);
    }
}
