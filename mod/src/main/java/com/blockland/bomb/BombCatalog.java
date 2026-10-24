package com.blockland.bomb;

import com.blockland.BlockLand;
import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * The bomb definition, read once from {@code /data/blockland/bomb/bomb.json} on the classpath (the same file on client
 * and server, so both agree on ranges and the blast).
 */
public final class BombCatalog {
    private static final String RESOURCE = "/data/blockland/bomb/bomb.json";
    private static BombData instance;

    private BombCatalog() {
    }

    public static synchronized BombData get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static BombData load() {
        try (InputStream in = BombCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                BombData data = new Gson().fromJson(reader, BombData.class);
                return data == null ? new BombData() : data;
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load the bomb definition", e);
            return new BombData();
        }
    }
}
