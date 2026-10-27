package com.blockland.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * What the shared vehicle code asks of the sides it runs on, without knowing them (vehicle spec §1): the server
 * (whether a player may use vehicles here, what to do after a vehicle stepped) and the client (who the local player is,
 * the driver's input this tick, what to do when a shell goes off). Each side sets its own hooks at start-up; until then
 * everything is allowed and nothing happens.
 */
public final class Vehicles {
    /** Server side. */
    public interface Server {
        Server OPEN = new Server() { };

        /** Whether {@code player} may get into or spawn a vehicle (mode setting {@code allowVehicles}). */
        default boolean mayUse(ServerPlayer player) {
            return true;
        }

        /** After a vehicle's server step: tell its driver the state (reconciliation). */
        default void stepped(VehicleEntity vehicle) {
        }

        /** A vehicle was destroyed (its wreck is about to blow up). */
        default void destroyed(VehicleEntity vehicle) {
        }
    }

    /** Client side. */
    public interface Client {
        Client NONE = new Client() { };

        @Nullable
        default Player localPlayer() {
            return null;
        }

        /** The local driver's input for this tick (unnumbered: the vehicle numbers it). */
        default VehicleControl input(VehicleEntity vehicle) {
            return VehicleControl.NONE;
        }

        /** Sends a numbered input to the server. */
        default void send(VehicleEntity vehicle, VehicleControl control) {
        }

        /** A shell went off at its position. */
        default void shellExploded(VehicleShell shell) {
        }

        /** A vehicle event for the effects and sounds (fire, hit, horn, destroyed, exploded). */
        default void event(VehicleEntity vehicle, byte event) {
        }
    }

    private static volatile Server server = Server.OPEN;
    private static volatile Client client = Client.NONE;

    private Vehicles() {
    }

    public static Server server() {
        return server;
    }

    public static Client client() {
        return client;
    }

    public static void setServer(@Nullable Server hooks) {
        server = hooks == null ? Server.OPEN : hooks;
    }

    public static void setClient(@Nullable Client hooks) {
        client = hooks == null ? Client.NONE : hooks;
    }
}
