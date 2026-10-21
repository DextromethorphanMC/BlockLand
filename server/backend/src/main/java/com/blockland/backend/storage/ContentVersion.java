package com.blockland.backend.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic version of all game content. Bumped on every write; Minecraft servers use it
 * (as an ETag) to know whether their configuration is stale.
 */
public class ContentVersion {
    private final Path file;
    private final AtomicLong value;

    public ContentVersion(Path file) {
        this.file = file;
        long initial = 1;
        try {
            if (Files.isRegularFile(file)) initial = Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException ignored) {
            // start over from 1
        }
        this.value = new AtomicLong(initial);
    }

    public long current() {
        return value.get();
    }

    public long bump() {
        long next = value.incrementAndGet();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(next));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return next;
    }
}
