package com.blockland.backend.gametype.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Movement rules every game type carries in its mode settings: {@code allowSlide} (players may slide: sprint, then
 * crouch). Each type picks its default. Must stay in sync with the mod's {@code GameInstance#allowSlide}, which treats
 * a missing value as "no".
 */
public final class MovementSettings {
    public static final String ALLOW_SLIDE = "allowSlide";

    private MovementSettings() {
    }

    public static void putDefaults(ObjectNode node, boolean allowSlide) {
        node.put(ALLOW_SLIDE, allowSlide);
    }

    public static void normalizeInto(JsonNode in, ObjectNode out, boolean allowSlide) {
        out.put(ALLOW_SLIDE, TeamArenaSettings.boolField(in, ALLOW_SLIDE, allowSlide));
    }
}
