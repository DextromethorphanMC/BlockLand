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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The daily check-in (每日签到, see {@link CheckInConfig} for the rules): what a player's cycle looks like today and the
 * check-in itself, once per day — asking again the same day changes nothing. A day's reward is granted through
 * {@link PlayerService#grant}, like a shop purchase, so it reaches the game the same way.
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
     * A player's check-in today.
     *
     * @param today            the day, ISO ({@code 2026-11-01}) in the config's zone
     * @param streak           days in a row up to today or yesterday, 0 once a day was missed
     * @param position         today's day in the cycle (0-based)
     * @param secondsToNextDay until the next check-in can be made
     */
    public record State(boolean enabled, String today, boolean claimedToday, int streak, int best, int total, int position,
                        long secondsToNextDay, List<Day> days) {
    }

    /**
     * @param claimed false when nothing happened: {@code already} checked in today, check-ins {@code disabled}
     * @param reward  the day checked in (null when disabled)
     */
    public record ClaimResult(boolean claimed, String reason, Day reward, State state) {
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

    /** Checks the player in for today: grants the day's reward, moves the streak on. Once per day. */
    public synchronized ClaimResult claim(String uuid) {
        CheckInConfig cfg = config.get();
        String id = normalize(uuid);
        if (players.status(id).isEmpty()) throw ApiException.notFound("玩家", uuid);
        CheckInRecord record = find(id);
        State before = state(cfg, record);
        if (!cfg.enabled()) return new ClaimResult(false, "disabled", null, before);
        if (before.claimedToday()) return new ClaimResult(false, "already", before.days().get(before.position()), before);

        LocalDate today = today(cfg);
        int streak = before.streak() + 1;
        CheckInConfig.Reward reward = cfg.days().get((streak - 1) % cfg.days().size());
        if (reward.grants()) players.grant(id, reward.entitlement(), reward.durationDays(), "checkin:" + today);
        CheckInRecord next = new CheckInRecord(id, today, streak, Math.max(streak, record == null ? 0 : record.best()),
                (record == null ? 0 : record.total()) + 1);
        records.save(next);
        State after = state(cfg, next);
        return new ClaimResult(true, "ok", after.days().get(after.position()), after);
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
        List<CheckInRecord> all = records.findAll();
        List<PlayerLine> recent = all.stream()
                .sorted(Comparator.comparing(CheckInRecord::lastDay, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingInt(CheckInRecord::streak).reversed()))
                .limit(MAX_RECENT)
                .map(r -> new PlayerLine(r.uuid(), players.status(r.uuid()).map(PlayerService.PlayerStatus::name).orElse(r.uuid()),
                        r.lastDay() == null ? null : r.lastDay().toString(), alive(r, today) ? r.streak() : 0, r.best(), r.total(),
                        today.equals(r.lastDay())))
                .toList();
        int claimedToday = (int) all.stream().filter(r -> today.equals(r.lastDay())).count();
        return new Stats(today.toString(), all.size(), claimedToday, recent);
    }

    /** Forgets a player's check-ins (the streak starts again; rewards already granted stay). */
    public synchronized void reset(String uuid) {
        if (!records.deleteById(normalize(uuid))) throw ApiException.notFound("签到记录", uuid);
    }

    // ---- helpers ----------------------------------------------------------------------------------

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
                record == null ? 0 : record.total(), position, Math.max(0, Duration.between(now, next).toSeconds()), days);
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
