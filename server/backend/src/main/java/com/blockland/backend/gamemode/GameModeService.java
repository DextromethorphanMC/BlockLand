package com.blockland.backend.gamemode;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.common.Ids;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.GameTypeRegistry;
import com.blockland.backend.kit.KitItem;
import com.blockland.backend.kit.KitValidator;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.media.MediaService;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class GameModeService {
    private final Repository<GameMode> modes;
    private final Repository<GameMap> maps;
    private final GameTypeRegistry types;
    private final KitValidator kits;
    private final MediaService media;

    public GameModeService(Repository<GameMode> modes, Repository<GameMap> maps, GameTypeRegistry types, KitValidator kits,
                           MediaService media) {
        this.modes = modes;
        this.maps = maps;
        this.types = types;
        this.kits = kits;
        this.media = media;
    }

    public record Request(String id, String title, String type, String description, Boolean enabled,
                          Integer timeLimitSeconds, JsonNode settings, List<KitItem> kit) {
    }

    public List<GameMode> list() {
        return modes.findAll();
    }

    public boolean exists(String id) {
        return modes.findById(id).isPresent();
    }

    public GameMode get(String id) {
        return modes.findById(id).orElseThrow(() -> ApiException.notFound("游戏模式", id));
    }

    public GameMode create(Request request) {
        if (request.id() != null && modes.findById(request.id()).isPresent()) {
            throw ApiException.conflict("模式 ID 已存在: " + request.id());
        }
        return modes.save(validate(request.id(), request, null));
    }

    public GameMode update(String id, Request request) {
        GameMode current = get(id);
        return modes.save(validate(id, request, current.coverVersion()));
    }

    public void delete(String id) {
        if (!modes.deleteById(id)) throw ApiException.notFound("游戏模式", id);
        media.delete(MediaService.MODE, id);
    }

    /** Cover shown on the mode's card in the lobby (wide image, e.g. 16:9). */
    public GameMode setCover(String id, byte[] data) {
        GameMode mode = get(id);
        String version = media.store(MediaService.MODE, id, data, 512, 288);
        return modes.save(mode.withCover(version, Instant.now()));
    }

    public List<String> warnings(GameMode mode) {
        return types.find(mode.type())
                .map(type -> type.warnings(mode, context()))
                .orElse(List.of("未知的游戏类型: " + mode.type()));
    }

    /** Re-validates every mode's settings, e.g. after a referenced map was deleted. */
    public void revalidateAll() {
        for (GameMode mode : modes.findAll()) {
            types.find(mode.type()).ifPresent(type -> {
                ObjectNode settings = type.normalizeSettings(mode.settings(), context(), new ArrayList<>());
                if (!settings.equals(mode.settings())) modes.save(mode.withSettings(settings, Instant.now()));
            });
        }
    }

    private GameMode validate(String id, Request request, String coverVersion) {
        List<String> errors = new ArrayList<>();
        Ids.check(id, "模式 ID", errors);
        if (request.title() == null || request.title().isBlank()) errors.add("标题不能为空");
        if (request.timeLimitSeconds() != null && (request.timeLimitSeconds() < 10 || request.timeLimitSeconds() > 24 * 3600)) {
            errors.add("存活时间必须在 10 秒到 24 小时之间");
        }
        GameTypeDefinition type = types.find(request.type()).orElse(null);
        ObjectNode settings = null;
        List<KitItem> kit = List.of();
        if (type == null) {
            errors.add("未知的游戏类型: " + request.type());
        } else {
            settings = type.normalizeSettings(request.settings(), context(), errors);
            if (type.usesKit()) kit = kits.normalize(request.kit(), errors);
        }
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        return new GameMode(id, request.title().trim(), type.id(),
                request.description() == null ? "" : request.description().trim(),
                request.enabled() == null || request.enabled(),
                request.timeLimitSeconds(), settings, kit, coverVersion, Instant.now());
    }

    private GameTypeDefinition.Context context() {
        return new GameTypeDefinition.Context(maps.findAll());
    }
}
