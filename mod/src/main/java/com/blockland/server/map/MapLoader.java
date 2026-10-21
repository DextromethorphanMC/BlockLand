package com.blockland.server.map;

import com.blockland.BlockLand;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.content.model.MapDef;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Turns a {@link MapDef} into a {@link MapBlueprint}: downloads the file through the
 * {@link ContentRepository}, parses it off-thread and caches the result per file hash.
 */
public final class MapLoader {
    private final List<MapFormat> formats = List.of(new StructureNbtFormat(), new SpongeSchematicFormat());
    private final Map<String, CompletableFuture<MapBlueprint>> cache = new ConcurrentHashMap<>();
    private final ContentRepository content;
    private final MinecraftServer server;

    public MapLoader(ContentRepository content, MinecraftServer server) {
        this.content = content;
        this.server = server;
    }

    public CompletableFuture<MapBlueprint> load(MapDef map) {
        String key = map.id + "@" + map.fileHash + "#" + map.spawnMarkerBlock;
        CompletableFuture<MapBlueprint> future = cache.computeIfAbsent(key, k -> content.mapFile(map)
                .thenApplyAsync(path -> parse(map, path), Util.backgroundExecutor()));
        // do not cache failures so a later attempt can retry
        future.whenComplete((blueprint, error) -> {
            if (error != null) cache.remove(key, future);
        });
        return future;
    }

    /** Warms the cache, e.g. right after a configuration sync, so matches start without waiting. */
    public void preload(MapDef map) {
        load(map).exceptionally(error -> {
            BlockLand.LOGGER.warn("[BlockLand] Failed to preload map {}: {}", map.id, error.getMessage());
            return null;
        });
    }

    /** Drops cached blueprints that are no longer referenced by the configuration. */
    public void retain(java.util.Collection<MapDef> maps) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (MapDef map : maps) keep.add(map.id + "@" + map.fileHash + "#" + map.spawnMarkerBlock);
        cache.keySet().removeIf(key -> !keep.contains(key));
    }

    private MapBlueprint parse(MapDef map, Path path) {
        MapFormat format = formats.stream()
                .filter(f -> f.id().equalsIgnoreCase(map.format == null ? "nbt" : map.format))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported map format: " + map.format));
        try {
            CompoundTag root = readNbt(path);
            Predicate<Block> skip = markerPredicate(map.spawnMarkerBlock);
            MapFormat.Context context = new MapFormat.Context(BuiltInRegistries.BLOCK, server.getFixerUpper());
            MapBlueprint blueprint = format.read(root, context, skip);
            BlockLand.LOGGER.info("[BlockLand] Parsed map {} ({}x{}x{}, {} blocks)", map.id,
                    blueprint.size().getX(), blueprint.size().getY(), blueprint.size().getZ(), blueprint.blocks().size());
            return blueprint;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read map " + map.id, e);
        }
    }

    private static Predicate<Block> markerPredicate(String markerBlock) {
        if (markerBlock == null || markerBlock.isBlank()) return block -> false;
        ResourceLocation id = ResourceLocation.tryParse(markerBlock.trim());
        if (id == null) return block -> false;
        return block -> id.equals(BuiltInRegistries.BLOCK.getKey(block));
    }

    private static CompoundTag readNbt(Path path) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path))) {
            raw.mark(2);
            int b1 = raw.read();
            int b2 = raw.read();
            raw.reset();
            boolean gzip = b1 == 0x1f && b2 == 0x8b;
            if (gzip) return NbtIo.readCompressed(raw, NbtAccounter.unlimitedHeap());
            return NbtIo.read(new DataInputStream(raw), NbtAccounter.unlimitedHeap());
        }
    }
}
