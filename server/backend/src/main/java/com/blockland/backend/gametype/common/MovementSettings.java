package com.blockland.backend.gametype.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Movement rules every game type carries in its mode settings: {@code allowSlide} (players may slide: sprint, then
 * crouch), {@code allowProne} (lie down and crawl) and {@code allowDive} (sprint, jump, go prone in the air). Each type
 * picks the slide's default; prone and dive follow it unless set. Must stay in sync with the mod's
 * {@code GameInstance#allowAction}, which treats a missing slide as "no" and a missing prone / dive as the slide's value.
 */
public final class MovementSettings {
    public static final String ALLOW_SLIDE = "allowSlide";
    public static final String ALLOW_PRONE = "allowProne";
    public static final String ALLOW_DIVE = "allowDive";

    private MovementSettings() {
    }

    public static void putDefaults(ObjectNode node, boolean allowSlide) {
        node.put(ALLOW_SLIDE, allowSlide);
        node.put(ALLOW_PRONE, allowSlide);
        node.put(ALLOW_DIVE, allowSlide);
    }

    public static void normalizeInto(JsonNode in, ObjectNode out, boolean allowSlide) {
        boolean slide = TeamArenaSettings.boolField(in, ALLOW_SLIDE, allowSlide);
        out.put(ALLOW_SLIDE, slide);
        // modes saved before prone and dive existed: as the slide
        out.put(ALLOW_PRONE, TeamArenaSettings.boolField(in, ALLOW_PRONE, slide));
        out.put(ALLOW_DIVE, TeamArenaSettings.boolField(in, ALLOW_DIVE, slide));
    }
}
