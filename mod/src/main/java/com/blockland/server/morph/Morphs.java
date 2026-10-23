package com.blockland.server.morph;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.S2CMorph;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/** Tells clients to draw a player with another entity's model (a game decides who looks like what and who sees it). */
public final class Morphs {
    private Morphs() {
    }

    /** @param entity entity type id, null = the player's own model again */
    public static void show(UUID player, @Nullable String entity, Iterable<ServerPlayer> viewers) {
        S2CMorph packet = new S2CMorph(player, entity == null ? "" : entity);
        for (ServerPlayer viewer : viewers) BLNetwork.sendTo(viewer, packet);
    }
}
