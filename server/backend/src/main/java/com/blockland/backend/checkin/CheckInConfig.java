package com.blockland.backend.checkin;

import com.blockland.backend.registry.InventoryRegistryService;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The daily check-in (每日签到): a cycle of {@link #days} rewards, one per day in a row. A day starts at midnight in
 * {@link #zone}. Checking in on consecutive days walks through the cycle and starts it again after its last day; a day
 * missed breaks the streak, and the next check-in counts as the cycle's first day again.
 *
 * @param enabled off: players see the cycle but cannot check in
 * @param zone    the time zone whose midnight starts a new day ({@code Asia/Shanghai})
 */
public record CheckInConfig(boolean enabled, String zone, List<Reward> days) {
    static final int MAX_DAYS = 31;
    static final int MAX_LABEL = 40;
    static final int MAX_DURATION_DAYS = 3650;
    /** A permission entitlement ({@code PRIORITY_QUEUE}); an inventory item has a colon ({@code graffiti:skull}). */
    private static final Pattern PERMISSION = Pattern.compile("^[A-Z][A-Z0-9_]{0,47}$");

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

    /** Seven days of graffiti and name cards the mod really has (see the published inventory registry). */
    public static final CheckInConfig DEFAULT = new CheckInConfig(true, "Asia/Shanghai", List.of(
            new Reward("", "graffiti:paw", 0),
            new Reward("", "card:ocean", 7),
            new Reward("", "graffiti:skull", 0),
            new Reward("", "card:sunset", 7),
            new Reward("", "graffiti:crown", 0),
            new Reward("", "card:circuit", 7),
            new Reward("", "graffiti:neon_heart", 0)));

    public CheckInConfig {
        zone = zone == null || zone.isBlank() ? "Asia/Shanghai" : zone.trim();
        days = days == null ? List.of() : List.copyOf(days);
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
            if (day.label().length() > MAX_LABEL) errors.add(which + "的名称不能超过 " + MAX_LABEL + " 个字");
            if (day.grants() && !InventoryRegistryService.ITEM_KEY.matcher(day.entitlement()).matches()
                    && !PERMISSION.matcher(day.entitlement()).matches()) {
                errors.add(which + "的奖励标识无效: " + day.entitlement());
            }
            if (day.durationDays() < 0 || day.durationDays() > MAX_DURATION_DAYS) {
                errors.add(which + "的时长必须在 0 到 " + MAX_DURATION_DAYS + " 天之间（0 = 永久）");
            }
        }
        return errors;
    }
}
