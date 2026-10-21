package com.blockland.server.content.model;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A playable mode configured in the backend ("小游戏管理").
 * {@link #settings} is opaque here and interpreted by the matching game type.
 */
public final class GameModeDef {
    public String id;
    public String title;
    /** Game type id, e.g. {@code SKYWARS}. */
    public String type;
    public String description = "";
    public boolean enabled = true;
    /** Optional match time limit ("存活时间") in seconds; null or <= 0 means unlimited. */
    public Integer timeLimitSeconds;
    public JsonObject settings = new JsonObject();
    /** Starting items, used by game types that support kits. */
    public List<KitItemDef> kit = new ArrayList<>();
    /** Version of the cover image ({@code mode/<id>}); null = no cover. */
    public String coverVersion;

    public int timeLimitOrZero() {
        return timeLimitSeconds == null || timeLimitSeconds <= 0 ? 0 : timeLimitSeconds;
    }

    public List<KitItemDef> kitOrEmpty() {
        return kit == null ? List.of() : kit;
    }
}
