package com.blockland.backend.gamemode;

import com.blockland.backend.gametype.common.TeamArenaSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mode settings every game type carries whatever its own settings are, applied after the type normalised its part, so
 * no type can drop them: {@code ranked} (排位: the mode's matches count on the server-wide ladder; default off) and
 * {@code allowGraffiti} (players may spray graffiti in its matches; default on) and the spectator rules for eliminated
 * players: {@code spectate} (watch the match; default on), {@code spectateFreeCam} (a free camera over the match;
 * default on) and {@code spectateSeconds} (0–600, 0 = until the match ends; players who respawn always watch only until
 * their respawn). Must stay in sync with the mod, which reads them with {@code GameModeDef.flag(...)} / the settings.
 */
public final class GenericModeSettings {
    public static final String RANKED = "ranked";
    public static final String ALLOW_GRAFFITI = "allowGraffiti";
    public static final String SPECTATE = "spectate";
    public static final String SPECTATE_FREE_CAM = "spectateFreeCam";
    public static final String SPECTATE_SECONDS = "spectateSeconds";

    private GenericModeSettings() {
    }

    /** Writes the generic keys from {@code raw} into the type's normalised {@code settings} (which it returns). */
    public static ObjectNode apply(JsonNode raw, ObjectNode settings) {
        JsonNode in = raw == null || !raw.isObject() ? settings : raw;
        settings.put(RANKED, TeamArenaSettings.boolField(in, RANKED, false));
        settings.put(ALLOW_GRAFFITI, TeamArenaSettings.boolField(in, ALLOW_GRAFFITI, true));
        settings.put(SPECTATE, TeamArenaSettings.boolField(in, SPECTATE, true));
        settings.put(SPECTATE_FREE_CAM, TeamArenaSettings.boolField(in, SPECTATE_FREE_CAM, true));
        JsonNode seconds = in.get(SPECTATE_SECONDS);
        int value = seconds != null && seconds.canConvertToInt() ? seconds.asInt() : 0;
        settings.put(SPECTATE_SECONDS, Math.max(0, Math.min(600, value)));
        return settings;
    }

    public static boolean ranked(JsonNode settings) {
        return settings != null && settings.path(RANKED).asBoolean(false);
    }
}
