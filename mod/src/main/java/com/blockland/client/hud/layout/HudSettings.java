package com.blockland.client.hud.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The HUD's layout as the player left it (UI spec §3), kept in {@code blockland-settings.json} as {@code hud}. */
public final class HudSettings {
    /** Where each moved element is (an element not here sits at its default place). */
    public Map<String, Placement> places = new LinkedHashMap<>();
    /** Elements the player turned off. */
    public List<String> hidden = new ArrayList<>();
    /** Each element's chosen {@link HudElement.Option} values: element id → option key → value. */
    public Map<String, Map<String, String>> options = new LinkedHashMap<>();
    /** The sizes the player gave resizable elements ({@link HudElement#resize}): element id → {width, height}. */
    public Map<String, int[]> sizes = new LinkedHashMap<>();
    /** BlockLand draws the health, food, armour, hotbar and chat (off: vanilla's own, where vanilla puts them). */
    public boolean replaceVanilla = true;
    /** The editor snaps a dragged element to the screen's edges and middle and to the other elements. */
    public boolean snap = true;
    /** How far from the screen's edges they snap (GUI pixels): 0 or 4. */
    public int snapMargin = 4;

    /** An element's place: its anchor and the offset of its own same point from the screen's (GUI pixels). */
    public static final class Placement {
        public String anchor = HudAnchor.TOP_LEFT.name();
        public int x, y;

        public Placement() {
        }

        public Placement(HudAnchor anchor, int x, int y) {
            this.anchor = anchor.name();
            this.x = x;
            this.y = y;
        }
    }

    public void sanitize() {
        if (places == null) places = new LinkedHashMap<>();
        if (hidden == null) hidden = new ArrayList<>();
        if (options == null) options = new LinkedHashMap<>();
        if (sizes == null) sizes = new LinkedHashMap<>();
        places.values().removeIf(p -> p == null);
        sizes.values().removeIf(s -> s == null || s.length != 2);
        snapMargin = snapMargin <= 0 ? 0 : 4;
    }
}
