package com.blockland.backend.registry;

import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Item ids reported by the Minecraft server (including modded items). Used for autocomplete
 * in the admin UI and to validate loot table entries. Empty until a server connected once.
 */
@Service
public class ItemRegistryService {
    private final ObjectMapper mapper;
    private final Path file;
    private volatile Set<String> items;

    public ItemRegistryService(ObjectMapper mapper, BlockLandProperties properties) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("item-registry.json");
        this.items = read();
    }

    public List<String> items() {
        return List.copyOf(items);
    }

    public boolean isKnown() {
        return !items.isEmpty();
    }

    public boolean contains(String itemId) {
        return items.contains(itemId);
    }

    public synchronized void replace(List<String> ids) {
        Set<String> next = new TreeSet<>(ids);
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        items = next;
    }

    private Set<String> read() {
        if (!Files.isRegularFile(file)) return new TreeSet<>();
        try {
            return new TreeSet<>(mapper.readValue(file.toFile(), new TypeReference<List<String>>() { }));
        } catch (IOException e) {
            return new TreeSet<>();
        }
    }
}
