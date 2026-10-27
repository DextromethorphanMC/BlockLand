package com.blockland.backpack;

import com.blockland.BlockLand;
import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The backpacks (UI spec §2): {@code data/blockland/backpack/backpacks.json}, read from the mod's own files when the mod
 * starts, since its entries become registered items — the same on the server and the client.
 */
public final class BackpackData {
    private static final String PATH = "/data/blockland/backpack/backpacks.json";
    /** Rows of 9 a backpack can have at most (the inventory menu reserves its storage slots once). */
    public static final int MAX_ROWS = 6;

    /** One backpack: item id (under {@code blockland}), storage rows of 9, the model's depth on the back (pixels). */
    public record Pack(String id, int rows, int depth) {
        public int slots() {
            return rows * 9;
        }
    }

    private static final class File {
        List<Entry> backpacks = new ArrayList<>();
    }

    private static final class Entry {
        String id;
        int rows = 1;
        int depth = 4;
    }

    private static List<Pack> packs;

    private BackpackData() {
    }

    public static synchronized List<Pack> packs() {
        if (packs == null) packs = load();
        return packs;
    }

    /** The storage slots the inventory menu reserves: the largest backpack's. */
    public static int maxSlots() {
        int rows = 0;
        for (Pack pack : packs()) rows = Math.max(rows, pack.rows());
        return Math.max(1, rows) * 9;
    }

    private static List<Pack> load() {
        List<Pack> out = new ArrayList<>();
        try (InputStream in = BackpackData.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("missing " + PATH);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, File.class);
                for (Entry e : file.backpacks) {
                    if (e.id == null || e.id.isBlank()) continue;
                    out.add(new Pack(e.id, Math.max(1, Math.min(MAX_ROWS, e.rows)), Math.max(2, Math.min(8, e.depth))));
                }
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Backpacks could not be read from {}", PATH, e);
        }
        return List.copyOf(out);
    }
}
