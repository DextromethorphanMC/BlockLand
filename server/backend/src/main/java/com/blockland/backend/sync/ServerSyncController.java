package com.blockland.backend.sync;

import com.blockland.backend.announcement.Announcement;
import com.blockland.backend.announcement.AnnouncementService;
import com.blockland.backend.common.ApiException;
import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.GameTypeRegistry;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapService;
import com.blockland.backend.modegroup.ModeGroup;
import com.blockland.backend.modegroup.ModeGroupService;
import com.blockland.backend.registry.GameTypeRegistryData;
import com.blockland.backend.registry.ItemRegistryService;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API used by the BlockLand Forge mod (authenticated with {@code X-Server-Token}).
 * The mod polls {@code /bootstrap} with {@code If-None-Match}; the response is the complete configuration.
 */
@RestController
@RequestMapping("/api/server")
public class ServerSyncController {
    private final ContentVersion version;
    private final Repository<GameMode> modes;
    private final Repository<GameMap> maps;
    private final GameTypeRegistry types;
    private final MapService mapService;
    private final ModeGroupService groups;
    private final ItemRegistryService items;
    private final GameTypeRegistryData typeRegistry;
    private final AnnouncementService announcements;

    public ServerSyncController(ContentVersion version, Repository<GameMode> modes, Repository<GameMap> maps,
                                GameTypeRegistry types, MapService mapService, ItemRegistryService items,
                                ModeGroupService groups, GameTypeRegistryData typeRegistry, AnnouncementService announcements) {
        this.version = version;
        this.modes = modes;
        this.maps = maps;
        this.types = types;
        this.mapService = mapService;
        this.groups = groups;
        this.items = items;
        this.typeRegistry = typeRegistry;
        this.announcements = announcements;
    }

    /**
     * @param typeData      per game type id, whatever that type needs on the server (e.g. SkyWars loot tables)
     * @param announcements lobby announcements, oldest first
     */
    public record Bootstrap(long version, List<GameMode> modes, List<ModeGroup> groups, List<GameMap> maps, Map<String, Object> typeData,
                            List<Announcement> announcements) {
    }

    @GetMapping("/bootstrap")
    public ResponseEntity<Bootstrap> bootstrap(@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        long current = version.current();
        String etag = "\"v" + current + "\"";
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        Map<String, Object> typeData = new LinkedHashMap<>();
        for (GameTypeDefinition type : types.all()) {
            Object data = type.syncData();
            if (data != null) typeData.put(type.id(), data);
        }
        Bootstrap body = new Bootstrap(current, modes.findAll(), groups.list(), maps.findAll(), typeData, announcements.published());
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    @GetMapping("/maps/{id}/file")
    public ResponseEntity<Resource> mapFile(@PathVariable String id) {
        Path file = mapService.file(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(new FileSystemResource(file));
    }

    /** The server reports its item registry (vanilla + mods) for autocomplete/validation in the admin UI. */
    @PostMapping("/registry/items")
    public ResponseEntity<Void> itemRegistry(@RequestBody List<String> ids) {
        items.replace(ids);
        return ResponseEntity.noContent().build();
    }

    /** The server publishes what one game type offers (e.g. 枪战感染 monsters) for the admin UI and mode warnings. */
    @PostMapping("/registry/game-types/{type}")
    public ResponseEntity<Void> gameTypeRegistry(@PathVariable String type, @RequestBody JsonNode body) {
        if (!GameTypeRegistryData.isValidTypeId(type)) throw ApiException.badRequest("游戏类型 ID 格式错误: " + type);
        if (body == null || !body.isObject()) throw ApiException.badRequest("请求内容必须是 JSON 对象");
        typeRegistry.replace(type, (ObjectNode) body);
        return ResponseEntity.noContent().build();
    }
}
