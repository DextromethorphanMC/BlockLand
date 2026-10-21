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

    public List<KitItem> normalize(List<KitItem> raw, List<String> errors) {
        List<KitItem> result = new ArrayList<>();
        if (raw == null) return result;
        Set<String> used = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            KitItem entry = raw.get(i);
            String row = "装备第 " + (i + 1) + " 行: ";
            String item = entry.item() == null ? "" : entry.item().trim();
            items.check(item, row, errors);
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

    public static boolean isValidSlot(String slot) {
        if (NAMED_SLOTS.contains(slot)) return true;
        try {
            int index = Integer.parseInt(slot);
            return index >= 0 && index <= 35;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
