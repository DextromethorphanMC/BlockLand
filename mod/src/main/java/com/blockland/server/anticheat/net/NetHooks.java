package com.blockland.server.anticheat.net;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Where the anti-cheat's mixins on vanilla's packet listeners land (anti-cheat spec §2): they only forward here, and
 * this only forwards to the listener the server installs (none = vanilla behaviour). Cheap enough for every outgoing
 * packet: anything but the few kinds the anti-cheat follows is dismissed with an {@code instanceof}.
 */
public final class NetHooks {
    /** What the anti-cheat does with them. */
    public interface Listener {
        /**
         * A movement packet on the server thread, before vanilla handles it.
         *
         * @param awaitingTeleport vanilla waits for the client to confirm a teleport (it ignores the move then)
         * @return false to drop the packet (the anti-cheat set the player back)
         */
        boolean move(ServerPlayer player, ServerboundMovePlayerPacket packet, boolean awaitingTeleport);

        /** The on-ground flag vanilla should use for the packet it is handling (the server's own verdict). */
        boolean onGround(ServerPlayer player, ServerboundMovePlayerPacket packet);

        /** A client tick-end packet arrived (netty thread): its arrival time. */
        void tickEndArrived(UUID player, long nanos);

        /** The same tick-end packet, handled on the server thread in packet order. */
        void tickEnd(ServerPlayer player);

        /** A pong to one of our pings, on the server thread in packet order; {@code nanos} = arrival time. */
        void pong(UUID player, int id, long nanos);

        /** A velocity or an explosion is being sent to the player (server thread). */
        void sending(ServerPlayer player, Packet<?> packet);
    }

    @Nullable
    private static volatile Listener listener;

    private NetHooks() {
    }

    public static void setListener(@Nullable Listener next) {
        listener = next;
    }

    public static boolean move(ServerPlayer player, ServerboundMovePlayerPacket packet, boolean awaitingTeleport) {
        Listener l = listener;
        return l == null || l.move(player, packet, awaitingTeleport);
    }

    public static boolean onGround(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        Listener l = listener;
        return l == null ? packet.isOnGround() : l.onGround(player, packet);
    }

    /** Called twice per packet by vanilla's thread hand-over: first on the netty thread, then on the server thread. */
    public static void tickEnd(ServerPlayer player) {
        Listener l = listener;
        if (l == null) return;
        MinecraftServer server = player.level().getServer();
        if (server.isSameThread()) l.tickEnd(player);
        else l.tickEndArrived(player.getUUID(), System.nanoTime());
    }

    /** Netty thread: vanilla does nothing with pongs; ours (negative ids) are queued behind the packets before them. */
    public static void pong(ServerPlayer player, int id) {
        Listener l = listener;
        if (l == null || id >= 0) return;
        long arrived = System.nanoTime();
        UUID uuid = player.getUUID();
        player.level().getServer().executeIfPossible(() -> {
            Listener current = listener;
            if (current != null) current.pong(uuid, id, arrived);
        });
    }

    public static void sending(ServerPlayer player, Packet<?> packet) {
        if (!(packet instanceof ClientboundSetEntityMotionPacket || packet instanceof ClientboundExplodePacket
                || packet instanceof ClientboundBundlePacket)) return;
        Listener l = listener;
        if (l != null) l.sending(player, packet);
    }
}
