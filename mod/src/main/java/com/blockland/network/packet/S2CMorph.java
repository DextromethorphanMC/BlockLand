package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Draw a player with another entity's model (e.g. a vanilla zombie), or normally again.
 *
 * @param entity entity type id, empty = the player's own model
 */
public record S2CMorph(UUID player, String entity) {
    public static void encode(S2CMorph msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.player);
        buf.writeUtf(msg.entity, 128);
    }

    public static S2CMorph decode(FriendlyByteBuf buf) {
        return new S2CMorph(buf.readUUID(), buf.readUtf(128));
    }
}
