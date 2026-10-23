package com.blockland.server.content;

import com.blockland.server.content.model.AnnouncementDef;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.content.model.ModeGroupDef;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable view of everything configured in the backend. Mirrors GET /api/server/bootstrap. */
public final class ContentSnapshot {
    public static final ContentSnapshot EMPTY = new ContentSnapshot();

    public long version;
    public List<GameModeDef> modes = new ArrayList<>();
    public List<ModeGroupDef> groups = new ArrayList<>();
    public List<MapDef> maps = new ArrayList<>();
    /** Per game type id: data only that type understands (e.g. SkyWars loot tables). */
    public Map<String, JsonElement> typeData = new LinkedHashMap<>();
    /** Published announcements, oldest first. */
    public List<AnnouncementDef> announcements = new ArrayList<>();

    private transient Map<String, GameModeDef> modeIndex;
    private transient Map<String, MapDef> mapIndex;

    /** Modes players can see and queue for right now (enabled and not past their end date). */
    public List<GameModeDef> enabledModes() {
        return modes.stream().filter(GameModeDef::isOpen).toList();
    }

    public Optional<GameModeDef> mode(String id) {
        if (modeIndex == null) modeIndex = index(modes, m -> m.id);
        return Optional.ofNullable(modeIndex.get(id));
    }

    public Optional<MapDef> map(String id) {
        if (mapIndex == null) mapIndex = index(maps, m -> m.id);
        return Optional.ofNullable(mapIndex.get(id));
    }

    public Optional<JsonElement> typeData(String typeId) {
        return typeData == null ? Optional.empty() : Optional.ofNullable(typeData.get(typeId));
    }

    private static <T> Map<String, T> index(List<T> list, java.util.function.Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        if (list != null) for (T t : list) map.put(key.apply(t), t);
        return Collections.unmodifiableMap(map);
    }
}
