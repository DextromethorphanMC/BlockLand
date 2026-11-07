package com.blockland.server.content;

import com.blockland.BlockLand;
import com.blockland.server.backend.BackendHttp;
import com.blockland.server.content.model.MapDef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link ContentRepository} backed by the BlockLand REST backend, with an on-disk cache so the
 * server keeps working with the last known configuration when the backend is unreachable.
 */
public final class RemoteContentRepository implements ContentRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BackendHttp http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> token;
    private final Path cacheDir;
    private final Executor mainThread;
    private final List<Consumer<ContentSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Path>> downloads = new ConcurrentHashMap<>();

    private volatile ContentSnapshot snapshot = ContentSnapshot.EMPTY;
    private volatile String etag;
    private volatile boolean lastRefreshFailed;

    public RemoteContentRepository(Supplier<String> baseUrl, Supplier<String> token, BackendHttp http, Path cacheDir,
                                   Executor mainThread) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = http;
        this.cacheDir = cacheDir;
        this.mainThread = mainThread;
    }

    /** Loads the last configuration written to disk, used until the first successful refresh. */
    public void loadCache() {
        Path file = cacheDir.resolve("bootstrap.json");
        if (!Files.isRegularFile(file)) return;
        try {
            ContentSnapshot cached = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ContentSnapshot.class);
            if (cached != null) {
                snapshot = cached;
                BlockLand.LOGGER.info("[BlockLand] Loaded cached configuration v{} ({} modes)", cached.version, cached.modes.size());
            }
        } catch (Exception e) {
            BlockLand.LOGGER.warn("[BlockLand] Failed to read cached configuration", e);
        }
    }

    @Override
    public ContentSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public CompletableFuture<Boolean> refresh() {
        HttpRequest.Builder request = request("/api/server/bootstrap").GET();
        String currentEtag = etag;
        if (currentEtag != null) request.header("If-None-Match", currentEtag);

        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() == 304) {
                        markRecovered();
                        return false;
                    }
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                    }
                    ContentSnapshot next = GSON.fromJson(response.body(), ContentSnapshot.class);
                    if (next == null) throw new IllegalStateException("Empty bootstrap response");
                    etag = response.headers().firstValue("ETag").orElse(null);
                    writeCache(response.body());
                    markRecovered();
                    boolean changed = next.version != snapshot.version || snapshot == ContentSnapshot.EMPTY;
                    snapshot = next;
                    if (changed) notifyListeners(next);
                    return changed;
                })
                .exceptionally(error -> {
                    if (!lastRefreshFailed) {
                        BlockLand.LOGGER.warn("[BlockLand] Backend {} unreachable, using cached configuration: {}",
                                baseUrl.get(), rootMessage(error));
                    }
                    lastRefreshFailed = true;
                    return false;
                });
    }

    @Override
    public CompletableFuture<Path> mapFile(MapDef map) {
        String hash = map.fileHash == null ? "nohash" : map.fileHash.substring(0, Math.min(16, map.fileHash.length()));
        String format = map.format == null ? "nbt" : map.format;
        Path target = cacheDir.resolve("maps").resolve(map.id + "-" + hash + "." + format);
        if (Files.isRegularFile(target)) return CompletableFuture.completedFuture(target);

        return downloads.computeIfAbsent(target.toString(), key -> {
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            HttpRequest request = request("/api/server/maps/" + map.id + "/file").GET().timeout(Duration.ofMinutes(5)).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofFile(temp))
                    .thenApply(response -> {
                        try {
                            if (response.statusCode() != 200) {
                                Files.deleteIfExists(temp);
                                throw new IllegalStateException("HTTP " + response.statusCode() + " downloading map " + map.id);
                            }
                            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                            BlockLand.LOGGER.info("[BlockLand] Downloaded map {} -> {}", map.id, target.getFileName());
                            return target;
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .whenComplete((path, error) -> downloads.remove(key));
        });
    }

    @Override
    public void addChangeListener(Consumer<ContentSnapshot> listener) {
        listeners.add(listener);
    }

    @Override
    public void publishItemRegistry(Collection<String> itemIds) {
        HttpRequest request = request("/api/server/registry/items")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(itemIds), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(error -> null);
    }

    @Override
    public void publishTypeRegistry(String typeId, JsonElement data) {
        HttpRequest request = request("/api/server/registry/game-types/" + java.net.URLEncoder.encode(typeId, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(data), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(error -> null);
    }

    private HttpRequest.Builder request(String path) {
        String base = baseUrl.get();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("X-Server-Token", token.get());
    }

    private void writeCache(String json) {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve("bootstrap.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            BlockLand.LOGGER.warn("[BlockLand] Failed to write configuration cache", e);
        }
    }

    private void markRecovered() {
        if (lastRefreshFailed) BlockLand.LOGGER.info("[BlockLand] Backend connection restored");
        lastRefreshFailed = false;
    }

    private void notifyListeners(ContentSnapshot next) {
        mainThread.execute(() -> listeners.forEach(listener -> listener.accept(next)));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
