package com.blockland.backend.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A map's ambience, the same for every game type: {@code "ambient": {"tumbleweeds": true}} next to the type's own map
 * settings. Game types clean only their own keys; {@link MapService} keeps this one around them when settings are
 * edited or the file is replaced. The mod reads it (tumbleweeds blowing through the arena).
 */
public final class MapAmbient {
    public static final String KEY = "ambient";
    public static final String TUMBLEWEEDS = "tumbleweeds";

    private MapAmbient() {
    }

    /** The cleaned ambience of edited settings, or null when there is none (or all of it is off). */
    public static ObjectNode normalize(JsonNode settings, List<String> errors) {
        JsonNode raw = settings == null || !settings.isObject() ? null : settings.get(KEY);
        if (raw == null || raw.isNull()) return null;
        if (!raw.isObject()) {
            errors.add("地图环境设置格式错误");
            return null;
        }
        JsonNode tumbleweeds = raw.get(TUMBLEWEEDS);
        if (tumbleweeds != null && !tumbleweeds.isNull() && !tumbleweeds.isBoolean()) {
            errors.add("风滚草 必须是开或关");
            return null;
        }
        if (tumbleweeds == null || !tumbleweeds.asBoolean()) return null;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put(TUMBLEWEEDS, true);
        return out;
    }

    /** The stored ambience of saved settings (already clean), or null. */
    public static ObjectNode of(JsonNode settings) {
        return normalize(settings, new ArrayList<>());
    }

    /** {@code settings} with the ambience put back, or taken out when it is null. */
    public static ObjectNode attach(ObjectNode settings, ObjectNode ambient) {
        if (ambient == null) settings.remove(KEY);
        else settings.set(KEY, ambient);
        return settings;
    }
}
