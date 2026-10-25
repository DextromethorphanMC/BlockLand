package com.blockland.client.craft;

import com.blockland.craft.Build;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.CraftPackets;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The player's saved 工匠 builds as last received from the server (the server keeps them). Client thread only; cleared
 * when the connection ends.
 */
public final class ClientBuilds {
    private static Map<String, Build> builds = Map.of();
    /** Keys of skins that must be owned which the player owns ({@code SkinKeys}). */
    private static Set<String> unlocked = Set.of();
    private static boolean received;
    /** Bumped on every update, so screens can tell a reply to their save from older state. */
    private static int version;
    private static String lastError = "";

    private ClientBuilds() {
    }

    static void update(CraftPackets.S2CBuilds packet) {
        builds = Map.copyOf(packet.builds());
        unlocked = Set.copyOf(packet.unlocked());
        received = true;
        lastError = packet.error();
        version++;
    }

    public static void clear() {
        builds = Map.of();
        unlocked = Set.of();
        received = false;
        lastError = "";
        version++;
    }

    /** Asks the server for the builds unless they are already known. */
    public static void requestIfNeeded() {
        if (!received) BLNetwork.sendToServer(CraftPackets.C2SRequest.INSTANCE);
    }

    /** The saved build of a weapon; empty = the weapon's default. */
    public static Optional<Build> get(String weaponId) {
        return Optional.ofNullable(builds.get(weaponId));
    }

    /** Whether the player owns the skin item with this key (free skins are not items: ask {@code SkinKeys}). */
    public static boolean owns(String skinKey) {
        return unlocked.contains(skinKey);
    }

    public static boolean received() {
        return received;
    }

    public static int version() {
        return version;
    }

    /** Translation key of why the server refused the last save, "" when it did not. */
    public static String lastError() {
        return lastError;
    }
}
