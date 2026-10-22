package com.blockland.backend.kit;

import com.blockland.backend.registry.ItemSpecs;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates and normalises kits for any game type that uses them. */
@Component
public class KitValidator {
    public static final Set<String> NAMED_SLOTS = Set.of("head", "chest", "legs", "feet", "offhand");

    private final ItemSpecs items;

    public KitValidator(ItemSpecs items) {
        this.items = items;
    }

    /** Items missing from the server's item registry are errors. */
    public List<KitItem> normalize(List<KitItem> raw, List<String> errors) {
        return normalize(raw, errors, errors);
    }

    /**
     * @param unknownItems receives items missing from the server's item registry instead of {@code errors}
     *                     (e.g. content prepared for items a newer mod version adds)
     */
    public List<KitItem> normalize(List<KitItem> raw, List<String> errors, List<String> unknownItems) {
        List<KitItem> result = new ArrayList<>();
        if (raw == null) return result;
        Set<String> used = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            KitItem entry = raw.get(i);
            String row = row(i);
            String item = entry.item() == null ? "" : entry.item().trim();
            if (items.checkFormat(item, row, errors)) items.checkRegistered(item, row, unknownItems);
            if (entry.count() < 1 || entry.count() > 64) errors.add(row + "数量应在 1-64 之间");
            String slot = entry.slot() == null ? "" : entry.slot().trim().toLowerCase(Locale.ROOT);
            if (!isValidSlot(slot)) {
                errors.add(row + "位置无效（0-35 或 head/chest/legs/feet/offhand）");
            } else if (!used.add(slot)) {
                errors.add(row + "位置 " + slot + " 重复");
            }
            result.add(new KitItem(item, entry.count(), slot));
        }
        return result;
    }

    /** Items of a saved kit that the server's item registry does not contain (shown as mode warnings). */
    public List<String> unknownItems(List<KitItem> kit) {
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < kit.size(); i++) items.checkRegistered(kit.get(i).item(), row(i), unknown);
        return unknown;
    }

    public static boolean isValidSlot(String slot) {
        if (NAMED_SLOTS.contains(slot)) return true;
        try {
            int index = Integer.parseInt(slot);
            return index >= 0 && index <= 35;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String row(int index) {
        return "装备第 " + (index + 1) + " 行: ";
    }
}
