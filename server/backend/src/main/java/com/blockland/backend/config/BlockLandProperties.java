package com.blockland.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "blockland")
public record BlockLandProperties(
        String dataDir,
        String adminToken,
        String serverToken,
        String webDir,
        boolean seedDemoData,
        List<String> corsOrigins
) {
    public BlockLandProperties {
        if (dataDir == null || dataDir.isBlank()) dataDir = "data";
        if (corsOrigins == null) corsOrigins = List.of();
    }

    public Path dataPath() {
        return Path.of(dataDir);
    }

    /** Null when no web directory is configured. */
    public Path webPath() {
        return webDir == null || webDir.isBlank() ? null : Path.of(webDir);
    }
}
