package com.blockland.network.packet;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * Text components as JSON strings, for packets that also travel in the lobby (configuration phase), where there are
 * no registry-aware buffers. Translatable components stay translatable: the client shows them in its own language.
 */
public final class TextJson {
    private TextJson() {
    }

    public static String write(Component component) {
        try {
            return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).getOrThrow().toString();
        } catch (Exception e) {
            // content that needs registries (item hover events…) is flattened to its text
            return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, Component.literal(component.getString()))
                    .getOrThrow().toString();
        }
    }

    public static Component read(String json) {
        try {
            return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        } catch (Exception e) {
            return Component.literal(json);
        }
    }
}
