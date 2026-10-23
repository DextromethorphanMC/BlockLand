package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/**
 * A party member's saved kit arrangement for the mode their leader queued them into
 * (the leader's own arrangement travels with {@link C2SJoinQueue}).
 */
public record C2SSubmitKit(String modeId, String kitSignature, List<String> kitSlots) {

    public static void encode(C2SSubmitKit msg, FriendlyByteBuf buf) {
        C2SJoinQueue.encode(new C2SJoinQueue(msg.modeId, "", msg.kitSignature, msg.kitSlots), buf);
    }

    public static C2SSubmitKit decode(FriendlyByteBuf buf) {
        C2SJoinQueue same = C2SJoinQueue.decode(buf);
        return new C2SSubmitKit(same.modeId(), same.kitSignature(), same.kitSlots());
    }
}
