package com.blockland.client.ambient;

import com.blockland.network.packet.AmbientPackets;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * What the match this client is in asks for (rendering spec §29), as the server last said: the box tumbleweeds roll in,
 * or none. Forgotten on leaving the world.
 */
final class AmbientZone {
    @Nullable
    private static AABB tumbleweeds;

    private AmbientZone() {
    }

    static void received(AmbientPackets.S2CAmbient msg) {
        tumbleweeds = msg.has(AmbientPackets.TUMBLEWEEDS)
                ? new AABB(msg.minX(), msg.minY(), msg.minZ(), msg.maxX(), msg.maxY(), msg.maxZ()) : null;
    }

    /** The match's box for tumbleweeds, or null. */
    @Nullable
    static AABB tumbleweeds() {
        return tumbleweeds;
    }

    static void clear() {
        tumbleweeds = null;
    }
}
