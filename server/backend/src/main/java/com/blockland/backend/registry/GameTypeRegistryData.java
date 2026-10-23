package com.blockland.backend.registry;

import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Content a Minecraft server publishes per game type (e.g. the monsters of 枪战感染), so neither the backend nor the
 * admin UI hardcodes it. Each entry is an arbitrary JSON object whose meaning belongs to that game type;
 * like the item registry it is empty until a server has published it, and the last published version is kept.
 */
@Service
public class GameTypeRegistryData {
    private static final Pattern TYPE_ID = Pattern.compile("^[A-Z0-9_]{1,32}$");

    private final ObjectMapper mapper;
    private final Path file;
    private volatile Map<String, ObjectNode> data;

    public GameTypeRegistryData(ObjectMapper mapper, BlockLandProperties properties) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("game-type-registry.json");
        this.data = read();
    }

    /** Game type ids as the mod uses them, e.g. {@code INFECTION} (case-insensitive). */
    public static boolean isValidTypeId(String typeId) {
        return typeId != null && TYPE_ID.matcher(typeId.toUpperCase(Locale.ROOT)).matches();
    }

    /** The type's last published data (a copy), or an empty object. */
    public ObjectNode get(String typeId) {
        ObjectNode node = typeId == null ? null : data.get(typeId.toUpperCase(Locale.ROOT));
        return node == null ? mapper.createObjectNode() : node.deepCopy();
    }

    public synchronized void replace(String typeId, ObjectNode value) {
        Map<String, ObjectNode> next = new TreeMap<>(data);
        next.put(typeId.toUpperCase(Locale.ROOT), value.deepCopy());
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        data = Collections.unmodifiableMap(next);
    }

    private Map<String, ObjectNode> read() {
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            return Collections.unmodifiableMap(new TreeMap<>(mapper.readValue(file.toFile(), new TypeReference<Map<String, ObjectNode>>() { })));
        } catch (IOException e) {
            return Map.of();
        }
    }
}
