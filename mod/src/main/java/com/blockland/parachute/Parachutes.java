package com.blockland.parachute;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * The parachute (降落伞, item {@code blockland:parachute}, worn in the chest slot) as other modules see it, on both
 * sides — the way {@code movement/action/BodyActions} is for slides and prone. Games (e.g. 大逃杀) only use this facade: whether someone's
 * canopy is open, and on the server opening it for them or handing one out. The parachute module (server service,
 * client prediction) installs the views at start-up.
 */
public final class Parachutes {
    /** What the server side offers. */
    public interface Server {
        Server NONE = new Server() {
            @Override public boolean isOpen(Entity entity) { return false; }
            @Override public boolean deploy(ServerPlayer player) { return false; }
            @Override public void close(ServerPlayer player) { }
            @Override public void equip(ServerPlayer player) { }
        };

        boolean isOpen(Entity entity);

        /** Opens the canopy of a falling player who wears a parachute; false when it cannot open (on the ground, none worn). */
        boolean deploy(ServerPlayer player);

        void close(ServerPlayer player);

        /** Puts a parachute in the player's chest slot (replacing what is there). */
        void equip(ServerPlayer player);
    }

    /** What the client side knows (its own prediction and the synced state of the players around). */
    public interface Client {
        Client NONE = entity -> false;

        boolean isOpen(Entity entity);
    }

    private static volatile Server server = Server.NONE;
    private static volatile Client client = Client.NONE;

    private Parachutes() {
    }

    public static void setServerView(@Nullable Server view) {
        server = view == null ? Server.NONE : view;
    }

    public static void setClientView(@Nullable Client view) {
        client = view == null ? Client.NONE : view;
    }

    /** The server side (no-op without a running server). */
    public static Server server() {
        return server;
    }

    /** Whether the entity's canopy is open, on whichever side the entity lives. */
    public static boolean isOpen(Entity entity) {
        return entity.level().isClientSide() ? client.isOpen(entity) : server.isOpen(entity);
    }
}
