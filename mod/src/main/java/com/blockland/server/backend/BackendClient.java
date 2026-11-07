package com.blockland.server.backend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** JSON-over-HTTP access to the BlockLand backend's {@code /api/server} endpoints (async, never blocks the server thread). */
public final class BackendClient {
    private static final Gson GSON = new GsonBuilder().create();

    private final BackendHttp http;
    private final Supplier<String> baseUrl;
    private final Supplier<String> token;

    public BackendClient(Supplier<String> baseUrl, Supplier<String> token, BackendHttp http) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = http;
    }

    public <T> CompletableFuture<T> get(String path, Type type) {
        return send(request(path).GET().build(), type);
    }

    public <T> CompletableFuture<T> post(String path, Object body, Type type) {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return send(request, type);
    }

    public <T> CompletableFuture<T> put(String path, Object body, Type type) {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return send(request, type);
    }

    public CompletableFuture<byte[]> getBytes(String path) {
        return http.sendAsync(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " " + path);
                    return response.body();
                });
    }

    private <T> CompletableFuture<T> send(HttpRequest request, Type type) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " " + request.uri().getPath() + ": " + response.body());
                    }
                    if (type == Void.class || response.body() == null || response.body().isEmpty()) return null;
                    return GSON.fromJson(response.body(), type);
                });
    }

    private HttpRequest.Builder request(String path) {
        String base = baseUrl.get();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("X-Server-Token", token.get());
    }
}
