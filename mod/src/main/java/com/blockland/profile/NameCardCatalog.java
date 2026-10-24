package com.blockland.profile;

import com.blockland.BlockLand;
import com.google.gson.Gson;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every name card (名片), read once from {@code /data/blockland/cards/cards.json} on the classpath — the same file on
 * client and server: the server offers them as inventory items ({@code card:<id>}), the client draws them. A card is
 * a texture (field names = JSON keys); an animated one is a vertical strip of {@code frames} played at {@code fps}
 * and/or a shader {@code effect} drawn over it ({@code aurora}, {@code holo}, {@code neon}, {@code inferno}).
 */
public final class NameCardCatalog {
    private static final String RESOURCE = "/data/blockland/cards/cards.json";
    /** The card everyone has and shows when nothing (or something unknown) is equipped. */
    public static final String DEFAULT = "default";
    private static NameCardCatalog instance;

    private final Map<String, Card> cards;

    /** One card as written in the file. */
    public static final class Card {
        public transient String id;
        /** Shown when the language file has no {@code blockland.card.<id>}. */
        public String name = "";
        public String description = "";
        /** {@code common}, {@code rare}, {@code epic}, {@code legendary}. */
        public String rarity = "common";
        /** PNG under {@code assets/blockland/}; frames stacked top to bottom when animated. */
        public String texture = "";
        public int frames = 1;
        public float fps = 8;
        /** Shader effect drawn over the texture; empty = none. */
        public String effect = "";
        /** Owned by everyone without an entitlement. */
        public boolean free;
        /** ARGB of the name on the card (hex {@code #RRGGBB} or {@code #AARRGGBB}). */
        public String text = "#FFFFFFFF";

        public boolean animated() {
            return frames > 1 || !effect.isEmpty();
        }
    }

    private static final class File {
        Map<String, Card> cards = new LinkedHashMap<>();
    }

    private NameCardCatalog(File file) {
        file.cards.forEach((id, card) -> card.id = id);
        this.cards = Collections.unmodifiableMap(file.cards);
        BlockLand.LOGGER.info("[BlockLand] Name cards: {}", cards.size());
    }

    public static synchronized NameCardCatalog get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static NameCardCatalog load() {
        try (InputStream in = NameCardCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                File file = new Gson().fromJson(reader, File.class);
                return new NameCardCatalog(file == null ? new File() : file);
            }
        } catch (Exception e) {
            BlockLand.LOGGER.error("[BlockLand] Failed to load name cards", e);
            return new NameCardCatalog(new File());
        }
    }

    public Collection<Card> all() {
        return cards.values();
    }

    @Nullable
    public Card card(@Nullable String id) {
        return id == null ? null : cards.get(id);
    }

    /** The card to draw for an id: the card itself, or the default one for null, empty and unknown ids. */
    @Nullable
    public Card orDefault(@Nullable String id) {
        Card card = card(id);
        return card != null ? card : cards.get(DEFAULT);
    }

    /** The inventory key of a card id. */
    public static String key(String id) {
        return "card:" + id;
    }

    /** The card id of an inventory key, or null when it is not a card key. */
    @Nullable
    public static String idOf(@Nullable String key) {
        return key != null && key.startsWith("card:") ? key.substring(5) : null;
    }
}
