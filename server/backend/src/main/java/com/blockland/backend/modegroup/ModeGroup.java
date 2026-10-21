package com.blockland.backend.modegroup;

import java.time.Instant;
import java.util.List;

/**
 * A group of game modes shown as one card in the lobby ("分组"); opening it lists its modes.
 *
 * @param modeIds      modes in display order (a mode may appear in several groups)
 * @param coverVersion version of the cover in the media store ({@code group/<id>}); null = no cover
 */
public record ModeGroup(
        String id,
        String title,
        String description,
        List<String> modeIds,
        boolean enabled,
        int sortOrder,
        String coverVersion,
        Instant updatedAt
) {
    public ModeGroup {
        modeIds = modeIds == null ? List.of() : List.copyOf(modeIds);
    }

    public ModeGroup withCover(String version, Instant time) {
        return new ModeGroup(id, title, description, modeIds, enabled, sortOrder, version, time);
    }

    public ModeGroup withModeIds(List<String> ids) {
        return new ModeGroup(id, title, description, ids, enabled, sortOrder, coverVersion, updatedAt);
    }
}
