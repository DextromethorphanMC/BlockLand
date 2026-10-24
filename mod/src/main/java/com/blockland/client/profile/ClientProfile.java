package com.blockland.client.profile;

import com.blockland.network.BLNetwork;
import com.blockland.network.ProfilePacketListeners;
import com.blockland.network.packet.ProfilePackets;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Client side of the lobby's 库存 and 个人主页 tabs: what the server last said about the player's inventory, profile and
 * the ladder (asked for when a tab opens, refreshed by the server when something changes), and the name card painter.
 * Nothing here decides anything; equipping is a request the server checks.
 */
public final class ClientProfile {
    private static List<ProfilePackets.Item> inventory = List.of();
    private static boolean inventoryLoaded;
    @Nullable
    private static ProfilePackets.S2CProfile profile;
    private static List<ProfilePackets.Line> leaderboard = List.of();
    private static boolean leaderboardLoaded;
    /** Bumped on every update, so screens can tell new data from old. */
    private static int version;

    private ClientProfile() {
    }

    public static void init(BusGroup modBus) {
        NameCards.setPainter(new NameCardRenderer());
        BLNetwork.setProfileClientListener(new ProfilePacketListeners.Client() {
            @Override
            public void onInventory(ProfilePackets.S2CInventory received) {
                inventory = received.items();
                inventoryLoaded = true;
                version++;
            }

            @Override
            public void onProfile(ProfilePackets.S2CProfile received) {
                profile = received;
                version++;
            }

            @Override
            public void onLeaderboard(ProfilePackets.S2CLeaderboard received) {
                leaderboard = received.lines();
                leaderboardLoaded = true;
                version++;
            }
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
    }

    private static void clear() {
        inventory = List.of();
        inventoryLoaded = false;
        profile = null;
        leaderboard = List.of();
        leaderboardLoaded = false;
        version++;
    }

    public static void request(ProfilePackets.Kind kind) {
        BLNetwork.sendToServer(new ProfilePackets.C2SRequest(kind));
    }

    /** Puts an owned item into a slot ({@code key} empty = takes the slot's item off); the server answers with the new state. */
    public static void equip(String slot, String key) {
        BLNetwork.sendToServer(new ProfilePackets.C2SEquip(slot, key));
    }

    public static List<ProfilePackets.Item> inventory() {
        return inventory;
    }

    public static boolean inventoryLoaded() {
        return inventoryLoaded;
    }

    @Nullable
    public static ProfilePackets.S2CProfile profile() {
        return profile;
    }

    public static List<ProfilePackets.Line> leaderboard() {
        return leaderboard;
    }

    public static boolean leaderboardLoaded() {
        return leaderboardLoaded;
    }

    public static int version() {
        return version;
    }
}
