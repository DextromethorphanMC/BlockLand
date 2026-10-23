package com.blockland.client.infection;

import net.minecraft.client.resources.language.I18n;

/**
 * Monster texts in the player's language: a translation by monster id when the language has one
 * ({@code blockland.monster.<id>}, {@code .desc}, {@code .skill}, {@code .skill.desc}), otherwise the server's data text.
 */
final class MonsterText {
    private MonsterText() {
    }

    static String name(String id, String fallback) {
        return text("blockland.monster." + id, fallback);
    }

    static String description(String id, String fallback) {
        return text("blockland.monster." + id + ".desc", fallback);
    }

    static String skill(String id, String fallback) {
        return text("blockland.monster." + id + ".skill", fallback);
    }

    static String skillDescription(String id, String fallback) {
        return text("blockland.monster." + id + ".skill.desc", fallback);
    }

    private static String text(String key, String fallback) {
        return !key.endsWith(".") && I18n.exists(key) ? I18n.get(key) : fallback;
    }
}
