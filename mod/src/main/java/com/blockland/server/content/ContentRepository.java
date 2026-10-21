package com.blockland.server.content;

import com.blockland.server.content.model.MapDef;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Source of game configuration. Game logic depends only on this interface,
 * so the backend transport (HTTP today) can be replaced without touching games.
 */
public interface ContentRepository {

    /** Latest known configuration; never null. */
    ContentSnapshot snapshot();

    /** Fetches configuration again. Completes with true when something changed. */
    CompletableFuture<Boolean> refresh();

    /** Resolves the local file for a map, downloading it if necessary. */
    CompletableFuture<Path> mapFile(MapDef map);

    /** Called (on the server thread) whenever the snapshot changes. */
    void addChangeListener(Consumer<ContentSnapshot> listener);

    /** Best-effort upload of registry data (e.g. item ids) so the admin UI can offer autocomplete. */
    void publishItemRegistry(Collection<String> itemIds);
}
