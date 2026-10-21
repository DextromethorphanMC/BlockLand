package com.blockland.server.content.model;

import java.util.ArrayList;
import java.util.List;

/** A group of modes shown as one card in the lobby (mirrors the backend's {@code ModeGroup}). */
public final class ModeGroupDef {
    public String id;
    public String title;
    public String description = "";
    /** Modes in display order. */
    public List<String> modeIds = new ArrayList<>();
    public boolean enabled = true;
    public int sortOrder;
    /** Version of the cover image ({@code group/<id>}); null = no cover. */
    public String coverVersion;
}
