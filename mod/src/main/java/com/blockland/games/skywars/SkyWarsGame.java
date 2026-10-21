package com.blockland.games.skywars;

import com.blockland.games.common.TeamArenaGame;
import com.blockland.games.skywars.loot.LootRoller;
import com.blockland.games.skywars.loot.LootTableDef;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.item.ItemStackFactory;
import com.blockland.server.map.MapBlueprint;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SkyWars on top of the shared team arena flow: fills (and optionally refills) chests from loot tables. */
final class SkyWarsGame extends TeamArenaGame {
    private final SkyWarsSettings settings;
    private final SkyWarsData.MapSettings mapSettings;
    private final LootRoller loot;
    private final Map<BlockPos, String> chests = new HashMap<>();

    SkyWarsGame(GameServices services, GameModeDef mode, SkyWarsSettings settings, MapDef map, MatchRoster players) {
        super(services, mode, settings.team(), map, players);
        this.settings = settings;
        this.mapSettings = SkyWarsData.mapSettings(map);
        this.loot = new LootRoller(new ItemStackFactory(services.server().registryAccess()));
    }

    @Override
    protected String gameName() {
        return "空岛战争";
    }

    @Override
    protected void onBlueprintLoaded(MapBlueprint blueprint) {
        for (SkyWarsData.Chest chest : mapSettings.chests) {
            chests.put(new BlockPos(chest.x, chest.y, chest.z), chest.lootTableId);
        }
        // containers in the file that were not configured get the map's default loot table
        for (MapBlueprint.Placement placement : blueprint.blocks()) {
            Block block = placement.state().getBlock();
            if (block instanceof ChestBlock || block instanceof BarrelBlock) chests.putIfAbsent(placement.pos(), null);
        }
    }

    @Override
    protected void onArenaReady() {
        fillChests();
    }

    @Override
    protected void onSecond() {
        int refill = settings.chestRefillSeconds();
        if (refill > 0 && elapsedSeconds() % refill == 0) {
            fillChests();
            broadcast(Component.literal("§6§l箱子已刷新！"));
            forEachOnline(p -> PlayerUtil.sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 0.8f));
        }
    }

    private void fillChests() {
        ContentSnapshot content = services.content().snapshot();
        ServerLevel level = arena().level();
        for (Map.Entry<BlockPos, String> chest : chests.entrySet()) {
            String tableId = chest.getValue() != null ? chest.getValue() : mapSettings.defaultLootTableId;
            LootTableDef table = SkyWarsData.lootTable(content, tableId).orElse(null);
            if (table == null) continue;
            BlockEntity blockEntity = level.getBlockEntity(arena().origin().offset(chest.getKey()));
            if (blockEntity instanceof RandomizableContainerBlockEntity randomizable) randomizable.setLootTable(null);
            if (blockEntity instanceof Container container) loot.fill(container, table, random);
        }
    }
}
