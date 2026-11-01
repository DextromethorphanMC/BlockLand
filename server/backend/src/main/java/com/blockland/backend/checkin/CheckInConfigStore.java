package com.blockland.backend.checkin;

import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The check-in config, one JSON object in {@code checkin-config.json} (the defaults until an admin saves one). */
@Component
public class CheckInConfigStore {
    private final ObjectMapper mapper;
    private final Path file;
    private volatile CheckInConfig config;

    public CheckInConfigStore(ObjectMapper mapper, BlockLandProperties properties) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("checkin-config.json");
        this.config = read();
    }

    public CheckInConfig get() {
        return config;
    }

    public synchronized void save(CheckInConfig next) {
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

    private CheckInConfig read() {
        if (!Files.isRegularFile(file)) return CheckInConfig.DEFAULT;
        try {
            CheckInConfig read = mapper.readValue(file.toFile(), CheckInConfig.class);
            return read.problems().isEmpty() ? read : CheckInConfig.DEFAULT;
        } catch (IOException e) {
            return CheckInConfig.DEFAULT;
        }
    }
}
