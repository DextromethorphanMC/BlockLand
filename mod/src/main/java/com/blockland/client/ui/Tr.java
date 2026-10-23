package com.blockland.client.ui;

import com.blockland.network.packet.TextJson;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Translated UI text from {@code assets/blockland/lang}; it follows the game's language setting (简体中文 / English). */
public final class Tr {
    /** Parsed server texts; the strings repeat every frame, the parse only once. */
    private static final Map<String, Component> PARSED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
            return size() > 256;
        }
    };

    private Tr() {
    }

    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }

    /**
     * Text the server sent in a plain string field: component JSON ({@link TextJson}, so translatable parts show in
     * this client's language) or ordinary text, which shows as it is.
     */
    public static String text(String serverText) {
        if (serverText == null || serverText.isEmpty()) return "";
        return PARSED.computeIfAbsent(serverText, TextJson::read).getString();
    }
}
