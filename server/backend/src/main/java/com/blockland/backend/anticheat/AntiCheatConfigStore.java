package com.blockland.backend.anticheat;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.ContentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The global anti-cheat config, one JSON object in {@code anticheat-config.json} (the built-in presets until an admin
 * saves one). It is game configuration: saving bumps the content version so game servers pick it up with the bootstrap.
 */
@Component
public class AntiCheatConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger(AntiCheatConfigStore.class);

    private final ObjectMapper mapper;
    private final Path file;
    private final ContentVersion version;
    private volatile AntiCheatConfig config;

    public AntiCheatConfigStore(ObjectMapper mapper, BlockLandProperties properties, ContentVersion version) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("anticheat-config.json");
        this.version = version;
        this.config = read();
    }

    public AntiCheatConfig get() {
        return config;
    }

    /** Validates an edited config (see {@link AntiCheatConfig#normalize}) and stores it; returns what was stored. */
    public synchronized AntiCheatConfig save(JsonNode raw) {
        List<String> errors = new ArrayList<>();
        AntiCheatConfig next = AntiCheatConfig.normalize(raw, errors);
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), next);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
        config = next;
        if (version != null) version.bump();
        return next;
    }

    private AntiCheatConfig read() {
        if (!Files.isRegularFile(file)) return AntiCheatConfig.DEFAULT;
        try {
            List<String> errors = new ArrayList<>();
            AntiCheatConfig read = AntiCheatConfig.normalize(mapper.readTree(file.toFile()), errors);
            if (errors.isEmpty()) return read;
            LOG.warn("{} has problems, using the built-in anti-cheat config: {}", file, errors);
        } catch (IOException e) {
            LOG.warn("Could not read {}, using the built-in anti-cheat config: {}", file, e.getMessage());
        }
        return AntiCheatConfig.DEFAULT;
    }
}
