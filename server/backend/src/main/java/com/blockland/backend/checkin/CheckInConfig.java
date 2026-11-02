package com.blockland.backend.checkin;

import com.blockland.backend.registry.InventoryRegistryService;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The daily check-in (每日签到): a cycle of {@link #days} rewards, one per day in a row. A day starts at midnight in
 * {@link #zone}. Checking in on consecutive days walks through the cycle and starts it again after its last day; a day
 * missed breaks the streak, and the next check-in counts as the cycle's first day again. On top of the cycle, the
 * {@link #milestones} pay extra on the check-in that reaches them: a streak of N days, N days altogether, or a date.
 *
 * @param enabled    off: players see the cycle but cannot check in
 * @param zone       the time zone whose midnight starts a new day ({@code Asia/Shanghai})
 * @param milestones the extra rewards (连续 / 累计 / 指定日期签到), in the order the lobby lists them
 */
public record CheckInConfig(boolean enabled, String zone, List<Reward> days, List<Milestone> milestones) {
    static final int MAX_DAYS = 31;
    static final int MAX_LABEL = 40;
    static final int MAX_DURATION_DAYS = 3650;
    static final int MAX_MILESTONES = 64;
    static final int MAX_MILESTONE_REWARDS = 8;
    static final int MAX_MILESTONE_DAYS = 3650;
    /** A permission entitlement ({@code PRIORITY_QUEUE}); an inventory item has a colon ({@code graffiti:skull}). */
    private static final Pattern PERMISSION = Pattern.compile("^[A-Z][A-Z0-9_]{0,47}$");
    private static final Pattern MILESTONE_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    /** {@code 2026-12-25} (that day only) or {@code 12-25} (every year). */
    private static final Pattern DATE = Pattern.compile("^(\\d{4}-)?\\d{2}-\\d{2}$");

    /**
     * One day of the cycle.
     *
     * @param label        what the day is called in game; blank shows the item's own name (or just the day)
     * @param entitlement  what checking in on this day grants ({@code <type>:<id>} item or a permission); blank = nothing
     * @param durationDays how long the grant lasts, 0 = permanent (a timed grant extends one the player already has)
     */
    public record Reward(String label, String entitlement, int durationDays) {
        public Reward {
            label = label == null ? "" : label.trim();
            entitlement = entitlement == null ? "" : entitlement.trim();
        }

        public boolean grants() {
            return !entitlement.isEmpty();
        }
    }

    /** What a milestone counts. */
    public enum Kind {
        /** {@link Milestone#days} check-ins in a row (连续签到). */
        STREAK,
        /** {@link Milestone#days} check-ins altogether (累计签到). */
        TOTAL,
        /** Checking in on {@link Milestone#date} (指定日期签到). */
        DATE
    }

    /**
     * An extra reward for reaching a check-in milestone, granted with that day's check-in. A milestone pays once per
     * player — a {@link Kind#STREAK} or {@link Kind#TOTAL} one the first time the count reaches {@link #days} (players
     * already past it get it at their next check-in) — unless it {@link #repeat}s: then on every check-in whose count
     * is a multiple of {@code days} (a new streak earns it again). A {@link Kind#DATE} one pays on its day, every year
     * for a {@code MM-dd} date.
     *
     * @param id      stable id: which milestones a player was paid is recorded by it
     * @param name    what the lobby calls it; blank = a name made from the rule
     * @param days    the count, for streak and total milestones
     * @param date    {@code yyyy-MM-dd} or {@code MM-dd} (yearly), for date milestones
     * @param rewards what it grants, each like a day of the cycle
     */
    public record Milestone(String id, Kind kind, String name, int days, String date, boolean repeat, boolean enabled,
                            List<Reward> rewards) {
        public Milestone {
            id = id == null ? "" : id.trim();
            name = name == null ? "" : name.trim();
            date = date == null ? "" : date.trim();
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
        }

        /** Whether this date milestone falls on {@code day}; false for other kinds. */
        public boolean on(LocalDate day) {
            return kind == Kind.DATE && day.equals(next(day));
        }

        /** The first day from {@code from} on that this date milestone falls on; null when it is over (or not a date one). */
        public LocalDate next(LocalDate from) {
            if (kind != Kind.DATE) return null;
            try {
                if (date.length() == 5) {
                    MonthDay md = MonthDay.parse("--" + date);
                    // 02-29 only exists in leap years: look ahead until one comes
                    for (int y = from.getYear(); y <= from.getYear() + 8; y++) {
                        if (!md.isValidYear(y)) continue;
                        LocalDate d = md.atYear(y);
                        if (!d.isBefore(from)) return d;
                    }
                    return null;
                }
                LocalDate d = LocalDate.parse(date);
                return d.isBefore(from) ? null : d;
            } catch (DateTimeException e) {
                return null;
            }
        }
    }

    /** Seven days of graffiti and name cards the mod really has (see the published inventory registry). */
    public static final CheckInConfig DEFAULT = new CheckInConfig(true, "Asia/Shanghai", List.of(
            new Reward("", "graffiti:paw", 0),
            new Reward("", "card:ocean", 7),
            new Reward("", "graffiti:skull", 0),
            new Reward("", "card:sunset", 7),
            new Reward("", "graffiti:crown", 0),
            new Reward("", "card:circuit", 7),
            new Reward("", "graffiti:neon_heart", 0)), List.of());

    @JsonCreator
    public CheckInConfig {
        zone = zone == null || zone.isBlank() ? "Asia/Shanghai" : zone.trim();
        days = days == null ? List.of() : List.copyOf(days);
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
    }

    /** A config without milestones. */
    public CheckInConfig(boolean enabled, String zone, List<Reward> days) {
        this(enabled, zone, days, List.of());
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /** The problems of an edited config (empty = valid). */
    public List<String> problems() {
        List<String> errors = new ArrayList<>();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            errors.add("无效的时区: " + zone);
        }
        if (days.isEmpty() || days.size() > MAX_DAYS) errors.add("签到周期必须是 1 到 " + MAX_DAYS + " 天");
        for (int i = 0; i < days.size(); i++) {
            Reward day = days.get(i);
            String which = "第 " + (i + 1) + " 天";
            if (day == null) {
                errors.add(which + "不能为空");
                continue;
            }
            rewardProblems(day, which, errors);
        }
        if (milestones.size() > MAX_MILESTONES) errors.add("额外奖励最多 " + MAX_MILESTONES + " 条");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < milestones.size(); i++) {
            Milestone m = milestones.get(i);
            String which = "额外奖励 " + (i + 1);
            if (m == null) {
                errors.add(which + "不能为空");
                continue;
            }
            if (!MILESTONE_ID.matcher(m.id()).matches()) {
                errors.add(which + "的标识无效（小写字母、数字、- 或 _，最多 32 个字符）: " + m.id());
            } else if (!ids.add(m.id())) {
                errors.add(which + "的标识重复: " + m.id());
            }
            if (m.kind() == null) {
                errors.add(which + "缺少类型");
                continue;
            }
            if (m.name().length() > MAX_LABEL) errors.add(which + "的名称不能超过 " + MAX_LABEL + " 个字");
            if (m.kind() == Kind.DATE) {
                if (!DATE.matcher(m.date()).matches() || !validDate(m.date())) {
                    errors.add(which + "的日期无效（yyyy-MM-dd，或每年的 MM-dd）: " + m.date());
                }
            } else if (m.days() < 1 || m.days() > MAX_MILESTONE_DAYS) {
                errors.add(which + "的天数必须在 1 到 " + MAX_MILESTONE_DAYS + " 之间");
            }
            if (m.rewards().isEmpty() || m.rewards().size() > MAX_MILESTONE_REWARDS) {
                errors.add(which + "需要 1 到 " + MAX_MILESTONE_REWARDS + " 个奖励");
            }
            for (int k = 0; k < m.rewards().size(); k++) {
                Reward r = m.rewards().get(k);
                String what = which + "的第 " + (k + 1) + " 个奖励";
                if (r == null || !r.grants()) {
                    errors.add(what + "不能为空");
                    continue;
                }
                rewardProblems(r, what, errors);
            }
        }
        return errors;
    }

    private static void rewardProblems(Reward reward, String which, List<String> errors) {
        if (reward.label().length() > MAX_LABEL) errors.add(which + "的名称不能超过 " + MAX_LABEL + " 个字");
        if (reward.grants() && !InventoryRegistryService.ITEM_KEY.matcher(reward.entitlement()).matches()
                && !PERMISSION.matcher(reward.entitlement()).matches()) {
            errors.add(which + "的奖励标识无效: " + reward.entitlement());
        }
        if (reward.durationDays() < 0 || reward.durationDays() > MAX_DURATION_DAYS) {
            errors.add(which + "的时长必须在 0 到 " + MAX_DURATION_DAYS + " 天之间（0 = 永久）");
        }
    }

    private static boolean validDate(String date) {
        try {
            if (date.length() == 5) MonthDay.parse("--" + date);
            else LocalDate.parse(date);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
