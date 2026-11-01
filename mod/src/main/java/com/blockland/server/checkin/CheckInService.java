package com.blockland.server.checkin;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.CheckInPacketListeners;
import com.blockland.network.packet.CheckInPackets;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.TextJson;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The lobby's daily check-in (每日签到) on the server: relays a player's cycle and their check-in between the lobby and
 * the backend, which keeps the streaks and decides (clients never talk to the backend). A day's reward is an entitlement
 * like a shop purchase: after a check-in the player's status is fetched again so it reaches what owning items unlocks.
 * Works by player id, so it serves the lobby.
 */
public final class CheckInService implements CheckInPacketListeners.Server {
    /** How a reward that is an inventory item looks. */
    public record Item(Component name, String icon, String rarity) {
    }

    private final MinecraftServer server;
    private final BackendClient backend;
    private final PlayerProfiles profiles;
    private final PlayerDirectory players;
    /** Players whose check-in is on its way to the backend: a second click waits for the first. */
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    private Function<String, Item> items = key -> null;

    // ---- backend JSON (mirrors CheckInService.State / Day / ClaimResult) --------------------------

    static final class DayJson {
        int index;
        String label;
        String entitlement;
        int durationDays;
        boolean claimed;
        boolean today;
    }

    static final class StateJson {
        boolean enabled;
        boolean claimedToday;
        int streak;
        int best;
        int total;
        int position;
        long secondsToNextDay;
        List<DayJson> days = new ArrayList<>();
    }

    static final class ClaimJson {
        boolean claimed;
        String reason;
        DayJson reward;
        StateJson state;
    }

    public CheckInService(MinecraftServer server, BackendClient backend, PlayerProfiles profiles, PlayerDirectory players) {
        this.server = server;
        this.backend = backend;
        this.profiles = profiles;
        this.players = players;
    }

    /** How a reward key that is an inventory item looks (null for anything else). */
    public void setItems(Function<String, Item> items) {
        this.items = items;
    }

    @Override
    public void onRequest(UUID player) {
        backend.<StateJson>get("/api/server/checkin/" + player, StateJson.class)
                .whenComplete((state, error) -> server.execute(() -> {
                    if (error != null || state == null) {
                        BlockLand.LOGGER.warn("[BlockLand] Check-in unavailable for {}: {}", player, error == null ? "no answer" : error.getMessage());
                        BLNetwork.sendTo(player, CheckInPackets.S2CState.failed("blockland.checkin.unreachable"));
                        return;
                    }
                    BLNetwork.sendTo(player, packet(state, -1));
                }));
    }

    @Override
    public void onClaim(UUID player) {
        if (players.name(player) == null || !claiming.add(player)) return;
        backend.<ClaimJson>post("/api/server/checkin/" + player + "/claim", null, ClaimJson.class)
                .whenComplete((result, error) -> server.execute(() -> {
                    claiming.remove(player);
                    if (error != null || result == null || result.state == null) {
                        BlockLand.LOGGER.warn("[BlockLand] Check-in failed for {}: {}", player, error == null ? "no answer" : error.getMessage());
                        players.notify(player, LobbyPackets.Level.ERROR, Component.translatable("blockland.checkin.failed"));
                        onRequest(player);
                        return;
                    }
                    if (!result.claimed) {
                        if ("disabled".equals(result.reason)) {
                            players.notify(player, LobbyPackets.Level.WARNING, Component.translatable("blockland.checkin.disabled"));
                        }
                        BLNetwork.sendTo(player, packet(result.state, -1));
                        return;
                    }
                    int day = result.reward == null ? result.state.position : result.reward.index;
                    players.notify(player, LobbyPackets.Level.SUCCESS, toast(result.reward, result.state.streak));
                    BLNetwork.sendTo(player, packet(result.state, day));
                    // the reward is an entitlement: what depends on owning items looks again once the status arrived
                    if (result.reward != null && result.reward.entitlement != null && !result.reward.entitlement.isEmpty()) {
                        profiles.refresh(player);
                    }
                }));
    }

    private Component toast(@Nullable DayJson reward, int streak) {
        Component name = reward == null ? null : rewardName(reward);
        return name == null ? Component.translatable("blockland.checkin.toast_plain", streak)
                : Component.translatable("blockland.checkin.toast", reward.index + 1, name);
    }

    /** What the day grants as the player should read it: the admin's label, else the item's name; null for nothing. */
    @Nullable
    private Component rewardName(DayJson day) {
        if (day.label != null && !day.label.isBlank()) return Component.literal(day.label);
        if (day.entitlement == null || day.entitlement.isEmpty()) return null;
        Item item = items.apply(day.entitlement);
        return item != null ? item.name() : Component.literal(day.entitlement);
    }

    private CheckInPackets.S2CState packet(StateJson state, int justClaimed) {
        List<CheckInPackets.Day> days = new ArrayList<>();
        for (DayJson d : state.days == null ? List.<DayJson>of() : state.days) {
            if (days.size() >= CheckInPackets.S2CState.MAX_DAYS) break;
            boolean reward = d.entitlement != null && !d.entitlement.isEmpty();
            Item item = reward ? items.apply(d.entitlement) : null;
            days.add(new CheckInPackets.Day(d.label == null ? "" : truncate(d.label, 40),
                    item == null ? "" : TextJson.write(item.name()), item == null ? "" : nullToEmpty(item.icon()),
                    item == null ? "" : nullToEmpty(item.rarity()), Math.max(0, d.durationDays), reward, d.claimed));
        }
        int position = days.isEmpty() ? 0 : Math.max(0, Math.min(days.size() - 1, state.position));
        return new CheckInPackets.S2CState("", state.enabled, state.claimedToday, Math.max(0, state.streak), Math.max(0, state.best),
                Math.max(0, state.total), position, Math.max(0, state.secondsToNextDay) * 1000L, days,
                justClaimed >= 0 && justClaimed < days.size() ? justClaimed : -1);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
