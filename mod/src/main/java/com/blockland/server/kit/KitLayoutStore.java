package com.blockland.server.kit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where the layout a player wants for a mode's kit is looked up when the kit is handed out. */
public interface KitLayoutStore {

    /**
     * @param signature identifies the kit contents the layout was made for; a changed kit invalidates it
     * @param slots     slot per kit entry, in kit order
     */
    record Layout(String signature, List<String> slots) {
    }

    Optional<Layout> load(UUID player, String modeId);

    void save(UUID player, String modeId, Layout layout);

    void remove(UUID player, String modeId);
}
