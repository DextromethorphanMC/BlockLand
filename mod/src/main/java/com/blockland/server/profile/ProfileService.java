package com.blockland.server.profile;

import com.blockland.network.BLNetwork;
import com.blockland.network.ProfilePacketListeners;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.ProfilePackets;
import com.blockland.profile.NameCardCatalog;
import com.blockland.server.inventory.InventoryService;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.ranked.Ranks;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The lobby's 库存 and 个人主页 tabs on the server: answers their requests (the inventory, the player's profile with their
 * card and standing from {@link Ranks}, the ladder) and puts items on through the inventory. Works by player id — the
 * tabs live in the lobby, where there is no world.
 */
public final class ProfileService implements ProfilePacketListeners.Server {
    /** Lines of the ladder the profile tab shows. */
    private static final int LEADERBOARD = 100;

    private final MinecraftServer server;
    private final PlayerDirectory players;
    private final PlayerProfiles profiles;
    private final CosmeticsService cosmetics;
    private final InventoryService inventory;

    public ProfileService(MinecraftServer server, PlayerDirectory players, PlayerProfiles profiles, CosmeticsService cosmetics,
                          InventoryService inventory) {
        this.server = server;
        this.players = players;
        this.profiles = profiles;
        this.cosmetics = cosmetics;
        this.inventory = inventory;
    }

    @Override
    public void onRequest(UUID player, ProfilePackets.Kind kind) {
        switch (kind) {
            case INVENTORY -> sendInventory(player);
            case PROFILE -> sendProfile(player);
            case LEADERBOARD -> sendLeaderboard(player);
        }
    }

    @Override
    public void onEquip(UUID player, String slot, String key) {
        Component error = inventory.equip(player, slot, key);
        if (error != null) {
            players.notify(player, LobbyPackets.Level.WARNING, error);
            sendInventory(player);
        }
        // a successful change comes back through the cosmetics listener (refresh)
    }

    /** The player's slots changed (or arrived): their open tabs show the new state. */
    public void refresh(UUID player) {
        if (!players.isOnline(player)) return;
        sendInventory(player);
        sendProfile(player);
    }

    public void sendInventory(UUID player) {
        BLNetwork.sendTo(player, new ProfilePackets.S2CInventory(inventory.inventory(player)));
    }

    public void sendProfile(UUID player) {
        Ranks.RankInfo rank = Ranks.get().rankOf(player);
        String name = players.name(player);
        BLNetwork.sendTo(player, new ProfilePackets.S2CProfile(name == null ? "" : name, cardOf(player), rank.score(), rank.tier().id(),
                rank.position(), rank.matches(), rank.wins(), profiles.playtimeSeconds(player), Ranks.get() != Ranks.View.NONE));
    }

    private void sendLeaderboard(UUID player) {
        Ranks.get().leaderboard(LEADERBOARD).whenComplete((entries, error) -> server.execute(() -> {
            if (!players.isOnline(player)) return;
            List<ProfilePackets.Line> lines = error != null || entries == null ? List.of() : entries.stream()
                    .map(e -> new ProfilePackets.Line(e.player().toString(), e.name(), e.score(), e.tier().id(), e.position(), cardOf(e.player())))
                    .toList();
            BLNetwork.sendTo(player, new ProfilePackets.S2CLeaderboard(lines));
        }));
    }

    /**
     * The id of the card a player wears ("" = the default card): only while they still own it (a timed card that ran
     * out falls back to the default), and only known for players on this server.
     */
    public String cardOf(UUID player) {
        @Nullable String key = cosmetics.get(player, Cosmetics.CARD);
        @Nullable String id = NameCardCatalog.idOf(key);
        if (id == null || NameCardCatalog.get().card(id) == null || !inventory.owns(player, key)) return "";
        return id;
    }
}
