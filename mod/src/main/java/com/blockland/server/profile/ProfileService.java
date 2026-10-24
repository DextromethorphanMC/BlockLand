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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public void onRequest(UUID player, ProfilePackets.Kind kind, String modeId) {
        switch (kind) {
            case INVENTORY -> sendInventory(player);
            case PROFILE -> sendProfile(player);
            case LEADERBOARD -> sendLeaderboard(player, modeId);
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

    /** The profile with the player's standing in every ranked mode on offer, then in other modes they played ranked. */
    public void sendProfile(UUID player) {
        Ranks.View view = Ranks.get();
        List<ProfilePackets.ModeRank> ranks = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (Ranks.RankedMode mode : view.rankedModes()) {
            listed.add(mode.id());
            ranks.add(modeRank(mode.id(), mode.title(), view.rankOf(player, mode.id())));
        }
        view.ranksOf(player).forEach((mode, rank) -> {
            if (listed.add(mode)) ranks.add(modeRank(mode, mode, rank));
        });
        String name = players.name(player);
        BLNetwork.sendTo(player, new ProfilePackets.S2CProfile(name == null ? "" : name, cardOf(player), ranks,
                profiles.playtimeSeconds(player), view != Ranks.View.NONE));
    }

    private static ProfilePackets.ModeRank modeRank(String modeId, String title, Ranks.RankInfo rank) {
        return new ProfilePackets.ModeRank(modeId, title, rank.score(), rank.tier().id(), rank.position(), rank.matches(), rank.wins());
    }

    private void sendLeaderboard(UUID player, String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            BLNetwork.sendTo(player, new ProfilePackets.S2CLeaderboard("", List.of()));
            return;
        }
        Ranks.get().leaderboard(modeId, LEADERBOARD).whenComplete((entries, error) -> server.execute(() -> {
            if (!players.isOnline(player)) return;
            List<ProfilePackets.Line> lines = error != null || entries == null ? List.of() : entries.stream()
                    .map(e -> new ProfilePackets.Line(e.player().toString(), e.name(), e.score(), e.tier().id(), e.position(), cardOf(e.player())))
                    .toList();
            BLNetwork.sendTo(player, new ProfilePackets.S2CLeaderboard(modeId, lines));
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
