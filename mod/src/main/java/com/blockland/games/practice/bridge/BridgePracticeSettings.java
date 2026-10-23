package com.blockland.games.practice.bridge;

import com.blockland.games.common.TeamArenaSettings;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 搭路练习 mode settings. Mirrors the backend's {@code BridgePracticeDefinition}.
 *
 * @param gapLength   blocks of void between the spawn island and the goal island
 * @param courseWidth width of the islands and of the lane the player may build in
 * @param voidDepth   blocks below the islands that count as falling off
 * @param block       the block handed out (endlessly)
 * @param timed       show a timer and keep a best time
 */
public record BridgePracticeSettings(int gapLength, int courseWidth, int voidDepth, Item block, boolean timed) {

    public static BridgePracticeSettings parse(JsonObject json) {
        int width = TeamArenaSettings.intOr(json, "courseWidth", 9, 3, 31);
        return new BridgePracticeSettings(
                TeamArenaSettings.intOr(json, "gapLength", 36, 8, 120),
                width % 2 == 0 ? width + 1 : width,
                TeamArenaSettings.intOr(json, "voidDepth", 10, 4, 40),
                block(json),
                TeamArenaSettings.boolOr(json, "timed", true));
    }

    private static Item block(JsonObject json) {
        String id = json != null && json.has("blockId") && json.get("blockId").isJsonPrimitive() ? json.get("blockId").getAsString() : "";
        ResourceLocation location = ResourceLocation.tryParse(id);
        Item item = location == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(location);
        return item instanceof BlockItem ? item : Items.WHITE_WOOL;
    }
}
