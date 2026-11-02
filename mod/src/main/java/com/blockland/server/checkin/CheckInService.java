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
import net.minecraft.network.chat.MutableComponent;
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
 * the backend, which keeps the streaks and decides (clients never talk to the backend). A day's reward, and those of the
 * milestones a check-in reaches (连续 / 累计 / 指定日期签到), are entitlements like a shop purchase: after a check-in the player's status is fetched again so it reaches what owning items unlocks.
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

    static final class RewardJson {
        String label;
        String entitlement;
        int durationDays;
    }

    /** Mirrors CheckInService.Milestone (and, without the progress, Bonus). */
    static final class MilestoneJson {
        String id;
        String kind;
        String name;
        int days;
        String date;
        boolean repeat;
        List<RewardJson> rewards = new ArrayList<>();
        int progress;
        int target;
        int checkInsLeft;
        boolean achieved;
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
        List<MilestoneJson> milestones = new ArrayList<>();
    }

    static final class ClaimJson {
        boolean claimed;
        String reason;
        DayJson reward;
        List<MilestoneJson> bonuses = new ArrayList<>();
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
                    BLNetwork.sendTo(player, packet(state, -1, List.of()));
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
                        BLNetwork.sendTo(player, packet(result.state, -1, List.of()));
                        return;
                    }
                    int day = result.reward == null ? result.state.position : result.reward.index;
                    players.notify(player, LobbyPackets.Level.SUCCESS, toast(result.reward, result.state.streak));
                    List<MilestoneJson> bonuses = result.bonuses == null ? List.of() : result.bonuses;
                    // each milestone the check-in reached: a toast of its own, with what it granted
                    for (MilestoneJson bonus : bonuses) {
                        players.notify(player, LobbyPackets.Level.SUCCESS,
                                Component.translatable("blockland.checkin.milestone_toast", milestoneName(bonus), rewardList(bonus)));
                    }
                    BLNetwork.sendTo(player, packet(result.state, day, bonuses));
                    // the rewards are entitlements: what depends on owning items looks again once the status arrived
                    boolean granted = !bonuses.isEmpty()
                            || result.reward != null && result.reward.entitlement != null && !result.reward.entitlement.isEmpty();
                    if (granted) profiles.refresh(player);
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

    /** The admin's name for a milestone, else its rule in words (连续签到 7 天, 每年 12-25 签到). */
    private static Component milestoneName(MilestoneJson m) {
        if (m.name != null && !m.name.isBlank()) return Component.literal(truncate(m.name, 40));
        CheckInPackets.MilestoneKind kind = kind(m);
        if (kind == CheckInPackets.MilestoneKind.DATE) {
            String date = m.date == null ? "" : m.date;
            return Component.translatable(date.length() == 5 ? "blockland.checkin.rule.date_yearly" : "blockland.checkin.rule.date", date);
        }
        String base = kind == CheckInPackets.MilestoneKind.STREAK ? "blockland.checkin.rule.streak" : "blockland.checkin.rule.total";
        return Component.translatable(m.repeat ? base + "_repeat" : base, m.days);
    }

    private static CheckInPackets.MilestoneKind kind(MilestoneJson m) {
        try {
            return CheckInPackets.MilestoneKind.valueOf(m.kind == null ? "" : m.kind);
        } catch (IllegalArgumentException e) {
            return CheckInPackets.MilestoneKind.TOTAL;
        }
    }

    /** A milestone's reward as the player should read it: the admin's label, else the item's name, else its key. */
    private Component rewardName(RewardJson reward) {
        if (reward.label != null && !reward.label.isBlank()) return Component.literal(truncate(reward.label, 40));
        String key = reward.entitlement == null ? "" : reward.entitlement;
        Item item = items.apply(key);
        return item != null ? item.name() : Component.literal(key);
    }

    /** What a milestone grants, as one list (A、B). */
    private Component rewardList(MilestoneJson m) {
        MutableComponent list = Component.empty();
        List<RewardJson> rewards = m.rewards == null ? List.of() : m.rewards;
        for (int i = 0; i < rewards.size(); i++) {
            if (i > 0) list.append(Component.translatable("blockland.checkin.list_separator"));
            list.append(rewardName(rewards.get(i)));
        }
        return list;
    }

    private CheckInPackets.Milestone milestone(MilestoneJson m) {
        List<CheckInPackets.Reward> rewards = new ArrayList<>();
        for (RewardJson r : m.rewards == null ? List.<RewardJson>of() : m.rewards) {
            if (rewards.size() >= CheckInPackets.Milestone.MAX_REWARDS) break;
            Item item = r.entitlement == null || r.entitlement.isEmpty() ? null : items.apply(r.entitlement);
            rewards.add(new CheckInPackets.Reward(TextJson.write(rewardName(r)), item == null ? "" : nullToEmpty(item.icon()),
                    item == null ? "" : nullToEmpty(item.rarity()), Math.max(0, r.durationDays)));
        }
        return new CheckInPackets.Milestone(kind(m), TextJson.write(milestoneName(m)), m.repeat, Math.max(0, m.progress),
                Math.max(0, m.target), Math.max(0, m.checkInsLeft), m.achieved, rewards);
    }

    private CheckInPackets.S2CState packet(StateJson state, int justClaimed, List<MilestoneJson> earned) {
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
        List<CheckInPackets.Milestone> milestones = new ArrayList<>();
        for (MilestoneJson m : state.milestones == null ? List.<MilestoneJson>of() : state.milestones) {
            if (milestones.size() >= CheckInPackets.S2CState.MAX_MILESTONES) break;
            milestones.add(milestone(m));
        }
        List<String> earnedNames = new ArrayList<>();
        for (MilestoneJson m : earned) {
            if (earnedNames.size() >= CheckInPackets.S2CState.MAX_MILESTONES) break;
            earnedNames.add(TextJson.write(milestoneName(m)));
        }
        return new CheckInPackets.S2CState("", state.enabled, state.claimedToday, Math.max(0, state.streak), Math.max(0, state.best),
                Math.max(0, state.total), position, Math.max(0, state.secondsToNextDay) * 1000L, days,
                justClaimed >= 0 && justClaimed < days.size() ? justClaimed : -1, milestones, earnedNames);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
