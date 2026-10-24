package com.blockland.backend.registry;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The things players can own (库存: graffiti, name cards …) as the Minecraft server published them at start-up, so
 * neither the backend nor the admin UI hardcodes the mod's content: the admin's item picker and product editor list
 * them, and cosmetics accept the free ones without an entitlement. The last published list is kept.
 */
@Service
public class InventoryRegistryService {
    /** {@code <type>:<id>}, lower case — the entitlement key of the item. */
    public static final Pattern ITEM_KEY = Pattern.compile("^[a-z0-9_]{1,32}:[a-z0-9_./-]{1,64}$");
    static final int MAX_ITEMS = 5000;

    /**
     * One ownable item.
     *
     * @param category the mod's category translation key (e.g. {@code blockland.inventory.category.graffiti})
     * @param name     display name in the server's default language
     * @param free     every player has it without an entitlement
     */
    public record Item(String key, String category, String name, String rarity, boolean free) {
    }

    private final ObjectMapper mapper;
    private final Path file;
    private volatile Map<String, Item> items;

    public InventoryRegistryService(ObjectMapper mapper, BlockLandProperties properties) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("inventory-registry.json");
        this.items = read();
    }

    public List<Item> items() {
        return List.copyOf(items.values());
    }

    public Optional<Item> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(items.get(key.toLowerCase(Locale.ROOT)));
    }

    public boolean isFree(String key) {
        return find(key).map(Item::free).orElse(false);
    }

    /** Replaces the list with what the server published; broken entries are reported, the rest kept. */
    public synchronized int replace(List<Item> published) {
        List<String> errors = new ArrayList<>();
        Map<String, Item> next = new LinkedHashMap<>();
        for (Item item : published == null ? List.<Item>of() : published) {
            if (item == null || item.key() == null) continue;
            String key = item.key().trim().toLowerCase(Locale.ROOT);
            if (!ITEM_KEY.matcher(key).matches()) {
                errors.add("无效的物品标识: " + item.key());
                continue;
            }
            next.put(key, new Item(key, item.category() == null ? "" : item.category(), item.name() == null ? key : item.name(),
                    item.rarity() == null ? "common" : item.rarity(), item.free()));
            if (next.size() > MAX_ITEMS) throw ApiException.badRequest("物品不能超过 " + MAX_ITEMS + " 个");
        }
        if (!errors.isEmpty() && next.isEmpty()) throw ApiException.invalid(errors);
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), List.copyOf(next.values()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        items = next;
        return next.size();
    }

    private Map<String, Item> read() {
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            Map<String, Item> map = new LinkedHashMap<>();
            for (Item item : mapper.readValue(file.toFile(), new TypeReference<List<Item>>() { })) map.put(item.key(), item);
            return map;
        } catch (IOException e) {
            return Map.of();
        }
    }
}
