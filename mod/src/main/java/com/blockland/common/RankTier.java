package com.blockland.common;

import java.util.Locale;

/**
 * 排位段位, lowest first: 煤炭, 钢铁, 黄金, 钻石, 猎首. Which score makes which tier is the backend's (its ranked
 * config, 猎首 also needs a place near the top of the ladder); this is only the shared vocabulary — id for packets and
 * JSON, translation key and colour for every screen that shows a tier.
 */
public enum RankTier {
    COAL(0xFF5A5F66), IRON(0xFFD8DCE2), GOLD(0xFFFFC94A), DIAMOND(0xFF5CF2E6), HEADHUNTER(0xFFFF4D4D);

    /** ARGB of the tier's badge and text. */
    public final int color;

    RankTier(int color) {
        this.color = color;
    }

    /** {@code coal}, {@code iron}, … (backend JSON, packets). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code blockland.rank.tier.coal} … */
    public String langKey() {
        return "blockland.rank.tier." + id();
    }

    /** The tier of an id; unknown or empty ids are {@link #COAL}. */
    public static RankTier of(String id) {
        if (id != null) {
            for (RankTier tier : values()) {
                if (tier.id().equalsIgnoreCase(id)) return tier;
            }
        }
        return COAL;
    }
}
