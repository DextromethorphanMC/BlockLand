package com.blockland.backend.gametype.skywars.loot;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.common.Ids;
import com.blockland.backend.gametype.skywars.SkyWarsMapSettings;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.registry.ItemSpecs;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class LootTableService {
    private final Repository<LootTable> tables;
    private final Repository<GameMap> maps;
    private final ItemSpecs items;
    private final ObjectMapper mapper;

    public LootTableService(@Qualifier("skyWarsLootTables") Repository<LootTable> tables, Repository<GameMap> maps,
                            ItemSpecs items, ObjectMapper mapper) {
        this.tables = tables;
        this.maps = maps;
        this.items = items;
        this.mapper = mapper;
    }

    public List<LootTable> list() {
        return tables.findAll();
    }

    public boolean exists(String id) {
        return id != null && tables.findById(id).isPresent();
    }

    public LootTable get(String id) {
        return tables.findById(id).orElseThrow(() -> ApiException.notFound("物资表", id));
    }

    public LootTable create(LootTable request) {
        if (request.id() != null && tables.findById(request.id()).isPresent()) {
            throw ApiException.conflict("物资表 ID 已存在: " + request.id());
        }
        return tables.save(validate(request.id(), request));
    }

    public LootTable update(String id, LootTable request) {
        get(id);
        return tables.save(validate(id, request));
    }

    public void delete(String id) {
        get(id);
        List<String> users = new ArrayList<>();
        for (GameMap map : maps.findAll()) {
            if (map.settings() == null) continue;
            SkyWarsMapSettings settings = mapper.convertValue(map.settings(), SkyWarsMapSettings.class);
            if (Objects.equals(settings.defaultLootTableId(), id)
                    || settings.chests().stream().anyMatch(c -> Objects.equals(c.lootTableId(), id))) {
                users.add(map.name());
            }
        }
        if (!users.isEmpty()) throw ApiException.conflict("物资表正在被地图使用: " + String.join("、", users));
        tables.deleteById(id);
    }

    private LootTable validate(String id, LootTable request) {
        List<String> errors = new ArrayList<>();
        Ids.check(id, "物资表 ID", errors);
        if (request.name() == null || request.name().isBlank()) errors.add("名称不能为空");
        if (request.minItems() < 0 || request.maxItems() < 0) errors.add("物品数量下限/上限不能为负数");
        if (request.maxItems() > 0 && request.maxItems() < request.minItems()) errors.add("物品数量上限不能小于下限");
        if (request.minItems() > 27 || request.maxItems() > 54) errors.add("物品数量超出箱子容量");

        List<LootTable.LootEntry> entries = new ArrayList<>();
        List<LootTable.LootEntry> raw = request.entries() == null ? List.of() : request.entries();
        for (int i = 0; i < raw.size(); i++) {
            LootTable.LootEntry entry = raw.get(i);
            String row = "第 " + (i + 1) + " 行: ";
            String item = entry.item() == null ? "" : entry.item().trim();
            items.check(item, row, errors);
            if (entry.minCount() < 1 || entry.maxCount() > 99 || entry.maxCount() < entry.minCount()) {
                errors.add(row + "数量范围应为 1-99 且上限不小于下限");
            }
            if (entry.chance() < 0 || entry.chance() > 100) errors.add(row + "概率应在 0-100% 之间");
            entries.add(new LootTable.LootEntry(item, entry.minCount(), entry.maxCount(), entry.chance()));
        }
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        return new LootTable(id, request.name().trim(), request.minItems(), request.maxItems(), entries, Instant.now());
    }
}
