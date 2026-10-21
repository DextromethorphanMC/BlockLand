package com.blockland.backend.map;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.common.Ids;
import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.gamemode.GameModeService;
import com.blockland.backend.gametype.GameTypeDefinition;
import com.blockland.backend.gametype.GameTypeRegistry;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Map files and their generic metadata. Type specific map data is delegated to the map's game type. */
@Service
public class MapService {
    private static final int DEFAULT_VOID_Y = -10;

    private final Repository<GameMap> maps;
    private final GameModeService gameModes;
    private final GameTypeRegistry types;
    private final MapFileAnalyzer analyzer;
    private final ObjectMapper mapper;
    private final Path mapDir;

    public MapService(Repository<GameMap> maps, GameModeService gameModes, GameTypeRegistry types,
                      MapFileAnalyzer analyzer, ObjectMapper mapper, BlockLandProperties properties) {
        this.maps = maps;
        this.gameModes = gameModes;
        this.types = types;
        this.analyzer = analyzer;
        this.mapper = mapper;
        this.mapDir = properties.dataPath().resolve("maps");
    }

    /** Editable metadata (everything except the file itself). {@code settings} is validated by the game type. */
    public record UpdateRequest(String name, String gameType, List<GameMap.TeamSpawn> teamSpawns,
                                Integer voidY, JsonNode settings) {
    }

    public List<GameMap> list() {
        return maps.findAll();
    }

    public boolean exists(String id) {
        return maps.findById(id).isPresent();
    }

    public GameMap get(String id) {
        return maps.findById(id).orElseThrow(() -> ApiException.notFound("地图", id));
    }

    public GameMap upload(String id, String name, String gameType, String spawnMarkerBlock, String fileName, byte[] data) {
        List<String> errors = new ArrayList<>();
        Ids.check(id, "地图 ID", errors);
        if (name == null || name.isBlank()) errors.add("地图名称不能为空");
        if (types.find(gameType).isEmpty()) errors.add("未知的游戏类型: " + gameType);
        if (data == null || data.length == 0) errors.add("请选择地图文件");
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        if (maps.findById(id).isPresent()) throw ApiException.conflict("地图 ID 已存在: " + id);

        GameTypeDefinition type = types.find(gameType).get();
        String format = MapFileAnalyzer.detectFormat(fileName);
        MapFileAnalyzer.Result result = analyzer.analyze(data, format, spawnMarkerBlock);
        storeFile(id, format, data, result.topView());

        GameMap map = new GameMap(id, name.trim(), type.id(), format, fileName, sha256(data), data.length,
                result.size(), blankToNull(spawnMarkerBlock), MapFileAnalyzer.clusterMarkers(result.markers(), result.size()),
                DEFAULT_VOID_Y, type.mapSettingsFromFile(result, null), Instant.now());
        return maps.save(map);
    }

    /** Replaces the map file; the game type decides which of its map settings survive (e.g. chests at the same coordinates). */
    public GameMap replaceFile(String id, String spawnMarkerBlock, String fileName, byte[] data) {
        GameMap current = get(id);
        String format = MapFileAnalyzer.detectFormat(fileName);
        String marker = spawnMarkerBlock != null ? blankToNull(spawnMarkerBlock) : current.spawnMarkerBlock();
        MapFileAnalyzer.Result result = analyzer.analyze(data, format, marker);
        deleteFiles(id);
        storeFile(id, format, data, result.topView());

        List<GameMap.TeamSpawn> spawns = result.markers().isEmpty() ? current.teamSpawns()
                : MapFileAnalyzer.clusterMarkers(result.markers(), result.size());
        ObjectNode settings = types.find(current.gameType())
                .map(type -> type.mapSettingsFromFile(result, current.settings()))
                .orElse(current.settings());
        GameMap map = new GameMap(id, current.name(), current.gameType(), format, fileName, sha256(data), data.length,
                result.size(), marker, spawns, current.voidY(), settings, Instant.now());
        return maps.save(map);
    }

    public GameMap update(String id, UpdateRequest request) {
        GameMap current = get(id);
        List<String> errors = new ArrayList<>();
        if (request.name() == null || request.name().isBlank()) errors.add("地图名称不能为空");
        String gameType = request.gameType() == null ? current.gameType() : request.gameType();
        GameTypeDefinition type = types.find(gameType).orElse(null);
        if (type == null) errors.add("未知的游戏类型: " + gameType);

        List<GameMap.TeamSpawn> spawns = new ArrayList<>();
        List<GameMap.TeamSpawn> rawSpawns = request.teamSpawns() == null ? List.of() : request.teamSpawns();
        for (int i = 0; i < rawSpawns.size(); i++) {
            GameMap.TeamSpawn spawn = rawSpawns.get(i);
            String spawnName = spawn.name() == null || spawn.name().isBlank() ? "出生点 " + (i + 1) : spawn.name().trim();
            List<GameMap.SpawnPoint> points = spawn.points() == null ? List.of() : spawn.points();
            if (points.isEmpty()) errors.add(spawnName + " 至少需要一个出生点");
            spawns.add(new GameMap.TeamSpawn(spawnName, points));
        }
        ObjectNode settings = type == null ? current.settings() : type.normalizeMapSettings(request.settings(), errors);
        if (!errors.isEmpty()) throw ApiException.invalid(errors);

        GameMap map = new GameMap(id, request.name().trim(), type.id(), current.format(), current.fileName(),
                current.fileHash(), current.fileSize(), current.size(), current.spawnMarkerBlock(), spawns,
                request.voidY() == null ? DEFAULT_VOID_Y : request.voidY(), settings, Instant.now());
        GameMap saved = maps.save(map);
        gameModes.revalidateAll();
        return saved;
    }

    public void delete(String id) {
        get(id);
        maps.deleteById(id);
        deleteFiles(id);
        gameModes.revalidateAll();
    }

    public Path file(String id) {
        GameMap map = get(id);
        Path file = mapDir.resolve(id + "." + map.format());
        if (!Files.isRegularFile(file)) throw ApiException.notFound("地图文件", id);
        return file;
    }

    public MapFileAnalyzer.TopView preview(String id) {
        get(id);
        Path file = mapDir.resolve(id + ".preview.json");
        if (!Files.isRegularFile(file)) throw ApiException.notFound("地图预览", id);
        try {
            return mapper.readValue(file.toFile(), MapFileAnalyzer.TopView.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void storeFile(String id, String format, byte[] data, MapFileAnalyzer.TopView preview) {
        try {
            Files.createDirectories(mapDir);
            Files.write(mapDir.resolve(id + "." + format), data);
            mapper.writeValue(mapDir.resolve(id + ".preview.json").toFile(), preview);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteFiles(String id) {
        try {
            for (String suffix : List.of(".nbt", ".schem", ".preview.json")) Files.deleteIfExists(mapDir.resolve(id + suffix));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
