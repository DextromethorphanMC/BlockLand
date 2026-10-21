package com.blockland.backend.registry;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Checks item specs in /give syntax ({@code namespace:id[components]}) against the server's item registry. */
@Component
public class ItemSpecs {
    private static final Pattern ITEM = Pattern.compile("^([a-z0-9_.\\-]+:)?[a-z0-9_./\\-]+(\\[.*])?$", Pattern.DOTALL);

    private final ItemRegistryService registry;

    public ItemSpecs(ItemRegistryService registry) {
        this.registry = registry;
    }

    public void check(String item, String rowLabel, List<String> errors) {
        if (item == null || item.isBlank() || !ITEM.matcher(item.trim()).matches()) {
            errors.add(rowLabel + "物品格式错误（示例 minecraft:iron_sword 或 minecraft:bow[enchantments={power:1}]）");
            return;
        }
        if (registry.isKnown()) {
            String baseId = item.contains("[") ? item.substring(0, item.indexOf('[')) : item;
            if (!baseId.contains(":")) baseId = "minecraft:" + baseId;
            if (!registry.contains(baseId.trim())) errors.add(rowLabel + "服务器中不存在物品 " + baseId);
        }
    }
}
