package com.blockland.common;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kit slot syntax shared by server and client: {@code 0}-{@code 35} (0-8 = hotbar, 9-35 = main inventory)
 * or {@code head}/{@code chest}/{@code legs}/{@code feet}/{@code offhand}.
 * Armour items are fixed; everything else may be rearranged by the player.
 */
public final class KitSlots {
    public static final List<String> ARMOR = List.of("head", "chest", "legs", "feet");
    public static final String OFFHAND = "offhand";
    public static final int MAIN_SLOTS = 36;

    private KitSlots() {
    }

    public static String normalize(String slot) {
        return slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String slot) {
        String s = normalize(slot);
        return ARMOR.contains(s) || OFFHAND.equals(s) || mainIndex(s) >= 0;
    }

    public static boolean isArmor(String slot) {
        return ARMOR.contains(normalize(slot));
    }

    /** @return 0-35 for inventory slots, -1 otherwise */
    public static int mainIndex(String slot) {
        try {
            int index = Integer.parseInt(normalize(slot));
            return index >= 0 && index < MAIN_SLOTS ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Checks a player's arrangement against the kit's default slots.
     *
     * @return null when valid, otherwise the reason
     */
    public static String validateLayout(List<String> defaults, List<String> layout) {
        if (layout.size() != defaults.size()) return "装备数量不匹配";
        Set<String> used = new HashSet<>();
        for (int i = 0; i < layout.size(); i++) {
            String slot = normalize(layout.get(i));
            String original = normalize(defaults.get(i));
            if (!isValid(slot)) return "无效的位置: " + slot;
            if (isArmor(original) ? !slot.equals(original) : isArmor(slot)) return "盔甲位置不能调整";
            if (!used.add(slot)) return "位置重复: " + slot;
        }
        return null;
    }
}
