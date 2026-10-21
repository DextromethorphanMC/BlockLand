package com.blockland.backend.modegroup;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.common.Ids;
import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.media.MediaService;
import com.blockland.backend.storage.Repository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mode groups. Groups only reference modes by id; when a mode is deleted it simply disappears from
 * every group's list (filtered on read), so the mode module never needs to know about groups.
 */
@Service
public class ModeGroupService {
    private final Repository<ModeGroup> groups;
    private final Repository<GameMode> modes;
    private final MediaService media;

    public ModeGroupService(@Qualifier("modeGroups") Repository<ModeGroup> groups, Repository<GameMode> modes, MediaService media) {
        this.groups = groups;
        this.modes = modes;
        this.media = media;
    }

    /** All groups by sort order, with references to deleted modes removed. */
    public List<ModeGroup> list() {
        Set<String> existing = modes.findAll().stream().map(GameMode::id).collect(Collectors.toSet());
        return groups.findAll().stream()
                .map(g -> g.withModeIds(g.modeIds().stream().filter(existing::contains).toList()))
                .sorted(Comparator.comparingInt(ModeGroup::sortOrder).thenComparing(ModeGroup::id))
                .toList();
    }

    public boolean exists(String id) {
        return groups.findById(id).isPresent();
    }

    public ModeGroup get(String id) {
        return groups.findById(id).orElseThrow(() -> ApiException.notFound("分组", id));
    }

    public ModeGroup create(ModeGroup request) {
        if (request.id() != null && exists(request.id())) throw ApiException.conflict("分组 ID 已存在: " + request.id());
        return groups.save(validate(request.id(), request, null));
    }

    public ModeGroup update(String id, ModeGroup request) {
        ModeGroup current = get(id);
        return groups.save(validate(id, request, current.coverVersion()));
    }

    public void delete(String id) {
        get(id);
        groups.deleteById(id);
        media.delete(MediaService.GROUP, id);
    }

    public ModeGroup setCover(String id, byte[] data) {
        ModeGroup group = get(id);
        return groups.save(group.withCover(media.store(MediaService.GROUP, id, data, 512, 288), Instant.now()));
    }

    private ModeGroup validate(String id, ModeGroup request, String coverVersion) {
        List<String> errors = new ArrayList<>();
        Ids.check(id, "分组 ID", errors);
        if (request.title() == null || request.title().isBlank()) errors.add("分组标题不能为空");
        Set<String> existing = modes.findAll().stream().map(GameMode::id).collect(Collectors.toSet());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String modeId : request.modeIds()) {
            if (!existing.contains(modeId)) errors.add("游戏模式不存在: " + modeId);
            else if (!ids.add(modeId)) errors.add("游戏模式重复: " + modeId);
        }
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        return new ModeGroup(id, request.title().trim(), request.description() == null ? "" : request.description().trim(),
                List.copyOf(ids), request.enabled(), request.sortOrder(), coverVersion, Instant.now());
    }
}
