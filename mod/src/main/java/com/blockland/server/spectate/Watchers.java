package com.blockland.server.spectate;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ViewmodelPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The players watching someone through their eyes: spectators whose vanilla camera is that player (whoever put them
 * there). Weapon services tell them about the hand's actions so their first-person view of the watched weapon moves
 * like the watched player's own; everyone else never gets these.
 */
public final class Watchers {
    private Watchers() {
    }

    /** Tells everyone watching {@code player} about one of their hand's actions. */
    public static void action(ServerPlayer player, ViewmodelPackets.Kind kind, int a, int b) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ViewmodelPackets.S2CAction packet = null;
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer == player || viewer.getCamera() != player) continue;
            if (packet == null) packet = new ViewmodelPackets.S2CAction(player.getId(), kind, a, b);
            BLNetwork.sendTo(viewer, packet);
        }
    }
}
