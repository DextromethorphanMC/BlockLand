package com.blockland.backend.checkin;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.storage.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The daily check-in (每日签到, see {@link CheckInConfig} for the rules): what a player's cycle and milestones look like
 * today and the check-in itself, once per day — asking again the same day changes nothing. A day's reward and the
 * milestones it reaches are granted through {@link PlayerService#grant}, like a shop purchase, so they reach the game
 * the same way.
 */
@Service
public class CheckInService {
    private static final int MAX_RECENT = 100;

    private final Repository<CheckInRecord> records;
    private final CheckInConfigStore config;
    private final PlayerService players;
    private final Clock clock;

    /**
     * One day of the cycle as a player sees it.
     *
     * @param claimed checked in on this day of the current cycle
     * @param today   the day the player checks in on today (or did already)
     */
    public record Day(int index, String label, String entitlement, int durationDays, boolean claimed, boolean today) {
    }

    /**
     * A milestone ({@link CheckInConfig.Milestone}) as a player sees it today. Date milestones that are over are left out.
     *
     * @param progress     streak or total now (0 for date milestones)
     * @param target       the count that pays next (the one just reached when {@code achieved} today by a repeating one)
     * @param checkInsLeft check-ins until it pays, today's counting while not made yet; for a date one, days until the date
     * @param nextDate     date milestones: the next day it falls on (ISO), else null
     * @param achieved     paid: a one-time one ever, a repeating or date one with today's check-in
     */
    public record Milestone(String id, CheckInConfig.Kind kind, String name, int days, String date, boolean repeat,
                            List<CheckInConfig.Reward> rewards, int progress, int target, int checkInsLeft, String nextDate,
                            boolean achieved) {
    }

    /** A milestone a check-in reached, and what it granted. */
    public record Bonus(String id, CheckInConfig.Kind kind, String name, int days, String date, List<CheckInConfig.Reward> rewards) {
    }

    /**
     * A player's check-in today.
     *
     * @param today            the day, ISO ({@code 2026-11-01}) in the config's zone
     * @param streak           days in a row up to today or yesterday, 0 once a day was missed
     * @param total            days checked in altogether (累计)
     * @param position         today's day in the cycle (0-based)
     * @param secondsToNextDay until the next check-in can be made
     */
    public record State(boolean enabled, String today, boolean claimedToday, int streak, int best, int total, int position,
                        long secondsToNextDay, List<Day> days, List<Milestone> milestones) {
    }

    /**
     * @param claimed false when nothing happened: {@code already} checked in today, check-ins {@code disabled}
     * @param reward  the day checked in (null when disabled)
     * @param bonuses the milestones this check-in reached (empty when nothing happened)
     */
    public record ClaimResult(boolean claimed, String reason, Day reward, List<Bonus> bonuses, State state) {
    }

    public record PlayerLine(String uuid, String name, String lastDay, int streak, int best, int total, boolean claimedToday) {
    }

    /** @param recent the players who checked in last, newest first */
    public record Stats(String today, int players, int claimedToday, List<PlayerLine> recent) {
    }

    @Autowired
    public CheckInService(@Qualifier("checkInRecords") Repository<CheckInRecord> records, CheckInConfigStore config, PlayerService players) {
        this(records, config, players, Clock.systemUTC());
    }

    CheckInService(Repository<CheckInRecord> records, CheckInConfigStore config, PlayerService players, Clock clock) {
        this.records = records;
        this.config = config;
        this.players = players;
        this.clock = clock;
    }

    // ---- called by Minecraft servers ----------------------------------------------------------

    public State state(String uuid) {
        return state(config.get(), find(uuid));
    }

    /** Checks the player in for today: grants the day's reward and the milestones reached, moves the streak on. Once per day. */
    public synchronized ClaimResult claim(String uuid) {
        CheckInConfig cfg = config.get();
        String id = normalize(uuid);
        if (players.status(id).isEmpty()) throw ApiException.notFound("玩家", uuid);
        CheckInRecord record = find(id);
        State before = state(cfg, record);
        if (!cfg.enabled()) return new ClaimResult(false, "disabled", null, List.of(), before);
        if (before.claimedToday()) return new ClaimResult(false, "already", before.days().get(before.position()), List.of(), before);

        LocalDate today = today(cfg);
        int streak = before.streak() + 1;
        int total = (record == null ? 0 : record.total()) + 1;
        CheckInConfig.Reward reward = cfg.days().get((streak - 1) % cfg.days().size());
        if (reward.grants()) players.grant(id, reward.entitlement(), reward.durationDays(), "checkin:" + today);

        List<String> paidOnce = new ArrayList<>(record == null ? List.of() : record.paidOnce());
        List<Bonus> bonuses = new ArrayList<>();
        for (CheckInConfig.Milestone m : cfg.milestones()) {
            if (!m.enabled() || !due(m, streak, total, today, paidOnce)) continue;
            for (CheckInConfig.Reward r : m.rewards()) {
                if (r.grants()) players.grant(id, r.entitlement(), r.durationDays(), "checkin:" + m.id() + ":" + today);
            }
            if (oneTime(m)) paidOnce.add(m.id());
            bonuses.add(new Bonus(m.id(), m.kind(), m.name(), m.days(), m.date(), m.rewards()));
        }

        CheckInRecord next = new CheckInRecord(id, today, streak, Math.max(streak, record == null ? 0 : record.best()), total, paidOnce);
        records.save(next);
        State after = state(cfg, next);
        return new ClaimResult(true, "ok", after.days().get(after.position()), bonuses, after);
    }

    // ---- admin ------------------------------------------------------------------------------------

    public CheckInConfig config() {
        return config.get();
    }

    public CheckInConfig saveConfig(CheckInConfig next) {
        if (next == null) throw ApiException.badRequest("缺少签到配置");
        List<String> problems = next.problems();
        if (!problems.isEmpty()) throw ApiException.invalid(problems);
        config.save(next);
        return next;
    }

    public Stats stats() {
        CheckInConfig cfg = config.get();
        LocalDate today = today(cfg);
        // a cleared record only remembers the milestones it was paid: not a check-in
        List<CheckInRecord> all = records.findAll().stream().filter(r -> r.lastDay() != null).toList();
        List<PlayerLine> recent = all.stream()
                .sorted(Comparator.comparing(CheckInRecord::lastDay, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingInt(CheckInRecord::streak).reversed()))
                .limit(MAX_RECENT)
                .map(r -> new PlayerLine(r.uuid(), players.status(r.uuid()).map(PlayerService.PlayerStatus::name).orElse(r.uuid()),
                        r.lastDay().toString(), alive(r, today) ? r.streak() : 0, r.best(), r.total(), today.equals(r.lastDay())))
                .toList();
        int claimedToday = (int) all.stream().filter(r -> today.equals(r.lastDay())).count();
        return new Stats(today.toString(), all.size(), claimedToday, recent);
    }

    /**
     * Forgets a player's check-ins (the streak and total start again; rewards already granted stay). The one-time
     * milestones already paid are remembered, so counting up again does not pay them twice.
     */
    public synchronized void reset(String uuid) {
        String id = normalize(uuid);
        CheckInRecord record = records.findById(id).orElse(null);
        if (record == null || record.lastDay() == null) throw ApiException.notFound("签到记录", uuid);
        if (record.paidOnce().isEmpty()) records.deleteById(id);
        else records.save(new CheckInRecord(id, null, 0, 0, 0, record.paidOnce()));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A milestone pays once ever (not repeating streak / total ones; date ones pay on their day). */
    private static boolean oneTime(CheckInConfig.Milestone m) {
        return m.kind() != CheckInConfig.Kind.DATE && !m.repeat();
    }

    /** Whether the check-in that makes {@code streak} and {@code total} today pays {@code m}. */
    private static boolean due(CheckInConfig.Milestone m, int streak, int total, LocalDate today, List<String> paidOnce) {
        if (m.kind() == CheckInConfig.Kind.DATE) return m.on(today);
        if (m.days() < 1) return false;
        int value = m.kind() == CheckInConfig.Kind.STREAK ? streak : total;
        return m.repeat() ? value % m.days() == 0 : value >= m.days() && !paidOnce.contains(m.id());
    }

    private List<Milestone> milestones(CheckInConfig cfg, CheckInRecord record, LocalDate today, boolean claimedToday, int streak) {
        List<Milestone> list = new ArrayList<>();
        List<String> paidOnce = record == null ? List.of() : record.paidOnce();
        int total = record == null ? 0 : record.total();
        for (CheckInConfig.Milestone m : cfg.milestones()) {
            if (!m.enabled()) continue;
            if (m.kind() == CheckInConfig.Kind.DATE) {
                LocalDate next = m.next(today);
                if (next == null) continue;
                boolean achieved = claimedToday && next.equals(today);
                list.add(new Milestone(m.id(), m.kind(), m.name(), m.days(), m.date(), m.repeat(), m.rewards(), 0, 0,
                        (int) ChronoUnit.DAYS.between(today, next), next.toString(), achieved));
                continue;
            }
            if (m.days() < 1) continue;
            int value = m.kind() == CheckInConfig.Kind.STREAK ? streak : total;
            int target, left;
            boolean achieved;
            if (m.repeat()) {
                achieved = claimedToday && value > 0 && value % m.days() == 0;
                left = achieved ? m.days() : m.days() - value % m.days();
                target = achieved ? value : value + left;
            } else {
                achieved = paidOnce.contains(m.id());
                target = m.days();
                // already past it (the milestone is newer than the count): the next check-in pays it
                left = achieved ? 0 : Math.max(1, m.days() - value);
            }
            list.add(new Milestone(m.id(), m.kind(), m.name(), m.days(), m.date(), m.repeat(), m.rewards(), value, target, left,
                    null, achieved));
        }
        return list;
    }

    private State state(CheckInConfig cfg, CheckInRecord record) {
        LocalDate today = today(cfg);
        boolean claimedToday = record != null && today.equals(record.lastDay());
        int streak = record != null && alive(record, today) ? record.streak() : 0;
        int n = cfg.days().size();
        // checked in today: today is the streak's last day; not yet: the day the next check-in counts for
        int position = claimedToday ? (streak - 1) % n : streak % n;
        List<Day> days = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CheckInConfig.Reward r = cfg.days().get(i);
            days.add(new Day(i, r.label(), r.entitlement(), r.durationDays(), claimedToday ? i <= position : i < position, i == position));
        }
        ZoneId zone = cfg.zoneId();
        Instant now = clock.instant();
        Instant next = today.plusDays(1).atStartOfDay(zone).toInstant();
        return new State(cfg.enabled(), today.toString(), claimedToday, streak, record == null ? 0 : record.best(),
                record == null ? 0 : record.total(), position, Math.max(0, Duration.between(now, next).toSeconds()), days,
                milestones(cfg, record, today, claimedToday, streak));
    }

    /** The streak still counts: the player checked in today or yesterday. */
    private static boolean alive(CheckInRecord record, LocalDate today) {
        return record.lastDay() != null && (today.equals(record.lastDay()) || today.minusDays(1).equals(record.lastDay()));
    }

    private LocalDate today(CheckInConfig cfg) {
        return LocalDate.ofInstant(clock.instant(), cfg.zoneId());
    }

    private CheckInRecord find(String uuid) {
        return uuid == null ? null : records.findById(normalize(uuid)).orElse(null);
    }

    private static String normalize(String uuid) {
        return uuid == null ? "" : uuid.toLowerCase(Locale.ROOT);
    }
}
