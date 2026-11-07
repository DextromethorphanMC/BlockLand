package com.blockland.backend.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The maps that ship with the backend, committed under {@code resources/maps/shipped}: per map {@code <id>.nbt} (or
 * {@code .schem}), {@code <id>.json} ({@link Shipped}: name, game type, void height, team spawns, the game type's
 * settings) and an optional {@code <id>.png} cover. They are the built-in maps' only source — seeds install them, nothing
 * generates a map at run time (the generators they came from are tools under {@code src/test}, see
 * {@code tools/maps/MAPS.md}). {@code tools/maps/export_maps.py} copies a map edited on a dev backend back into them.
 */
@Service
public class ShippedMaps {
    static final String ROOT = "/maps/shipped/";
    private static final List<String> FORMATS = List.of("nbt", "schem");

    private final MapService maps;
    private final ObjectMapper mapper;

    public ShippedMaps(MapService maps, ObjectMapper mapper) {
        this.maps = maps;
        this.mapper = mapper;
    }

    /** {@code <id>.json} of a shipped map. */
    public record Meta(String id, String name, String gameType, String spawnMarkerBlock, Integer voidY,
                       List<GameMap.TeamSpawn> teamSpawns, ObjectNode settings) {
    }

    /** A shipped map: its metadata, its file and its cover (null = none). */
    public record Shipped(Meta meta, String format, byte[] data, byte[] cover) {
        public String id() {
            return meta.id();
        }

        public String fileName() {
            return meta.id() + "." + format;
        }
    }

    public Shipped get(String id) {
        Meta meta;
        try (InputStream in = open(id + ".json")) {
            if (in == null) throw new IllegalArgumentException("No shipped map " + id + " (" + ROOT + id + ".json)");
            meta = mapper.readValue(in, Meta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable shipped map " + id, e);
        }
        if (!id.equals(meta.id())) throw new IllegalStateException("Shipped map " + id + ".json names " + meta.id());
        for (String format : FORMATS) {
            byte[] data = read(id + "." + format);
            if (data != null) return new Shipped(meta, format, data, read(id + ".png"));
        }
        throw new IllegalStateException("Shipped map " + id + " has no map file (" + String.join(" / ", FORMATS) + ")");
    }

    /** Installs a shipped map that is missing here: its file, metadata and cover. False when the id is already taken. */
    public boolean install(String id) {
        if (maps.exists(id)) return false;
        Shipped shipped = get(id);
        Meta meta = shipped.meta();
        GameMap map = maps.upload(id, meta.name(), meta.gameType(), meta.spawnMarkerBlock(), shipped.fileName(), shipped.data());
        maps.update(map.id(), new MapService.UpdateRequest(meta.name(), meta.gameType(), meta.teamSpawns(), meta.voidY(),
                meta.settings() == null ? null : meta.settings().deepCopy()));
        if (shipped.cover() != null) maps.setCover(id, shipped.cover());
        return true;
    }

    /**
     * Brings an installed map up to its shipped version (seeds that fix a map): when the stored file differs, it is
     * replaced and the spawns and game type settings follow the shipped map; the name, the void height and the ambience
     * stay as the admin left them. False when the file was already the shipped one.
     */
    public boolean refresh(String id) {
        Shipped shipped = get(id);
        if (!maps.replaceGeneratedFile(id, shipped.fileName(), shipped.data())) return false;
        GameMap map = maps.get(id);
        Meta meta = shipped.meta();
        ObjectNode settings = meta.settings() == null ? mapper.createObjectNode() : meta.settings().deepCopy();
        MapAmbient.attach(settings, MapAmbient.of(map.settings()));
        maps.update(id, new MapService.UpdateRequest(map.name(), map.gameType(), meta.teamSpawns(), map.voidY(), settings));
        return true;
    }

    private static InputStream open(String name) {
        return ShippedMaps.class.getResourceAsStream(ROOT + name);
    }

    private static byte[] read(String name) {
        try (InputStream in = open(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable shipped map file " + name, e);
        }
    }
}
