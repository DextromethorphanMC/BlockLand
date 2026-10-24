package com.blockland.backend.ranked;

import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The ranked config, one JSON object in {@code ranked-config.json} (the defaults until an admin saves one). */
@Component
public class RankedConfigStore {
    private final ObjectMapper mapper;
    private final Path file;
    private volatile RankedConfig config;

    public RankedConfigStore(ObjectMapper mapper, BlockLandProperties properties) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("ranked-config.json");
        this.config = read();
    }

    public RankedConfig get() {
        return config;
    }

    public synchronized void save(RankedConfig next) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), next);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
        config = next;
    }

    private RankedConfig read() {
        if (!Files.isRegularFile(file)) return RankedConfig.DEFAULT;
        try {
            RankedConfig read = mapper.readValue(file.toFile(), RankedConfig.class);
            return read.problems().isEmpty() ? read : RankedConfig.DEFAULT;
        } catch (IOException e) {
            return RankedConfig.DEFAULT;
        }
    }
}
