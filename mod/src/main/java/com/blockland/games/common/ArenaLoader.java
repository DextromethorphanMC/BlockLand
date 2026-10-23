package com.blockland.games.common;

import com.blockland.BlockLand;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.map.MapBlueprint;
import com.blockland.server.world.PasteJob;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Puts a map into the world for a match: load (and cache) the file, allocate an arena, paste it in batches.
 * Callbacks run on the server thread and are skipped once the match was disposed in the meantime.
 */
public final class ArenaLoader {
    private ArenaLoader() {
    }

    /**
     * @param disposed  whether the match was disposed meanwhile (then nothing is called)
     * @param onLoaded  the file was parsed and an arena allocated; nothing is in the world yet
     * @param onPasted  the map is in the world
     * @param onFailure what went wrong, for the players
     */
    public static void load(GameServices services, MapDef map, BooleanSupplier disposed,
                            BiConsumer<Arena, MapBlueprint> onLoaded, Consumer<Arena> onPasted, Consumer<Component> onFailure) {
        services.maps().load(map).whenComplete((blueprint, error) -> services.server().execute(() -> {
            if (disposed.getAsBoolean()) return;
            if (error != null) {
                BlockLand.LOGGER.error("[BlockLand] Failed to load map {}", map.id, error);
                onFailure.accept(Component.translatable("blockland.arena.load_failed"));
                return;
            }
            Arena arena = services.arenas().allocate(blueprint.size());
            onLoaded.accept(arena, blueprint);
            services.jobs().submit(new PasteJob(arena.level(), arena.origin(), blueprint)).whenComplete((v, pasteError) -> {
                if (disposed.getAsBoolean()) return;
                if (pasteError != null) {
                    onFailure.accept(Component.translatable("blockland.arena.paste_failed"));
                    return;
                }
                onPasted.accept(arena);
            });
        }));
    }
}
