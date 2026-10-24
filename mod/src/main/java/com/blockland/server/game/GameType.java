package com.blockland.server.game;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.KitItemDef;
import com.blockland.server.content.model.MapDef;
import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A kind of minigame (e.g. SkyWars). Game modes configured in the backend reference a type by {@link #id()}
 * and carry type specific settings that only the type itself interprets.
 */
public interface GameType {

    /** Stable id used in the backend, e.g. {@code SKYWARS}. */
    String id();

    String displayName();

    /** ARGB colour used for this type in the lobby UI. */
    int accentColor();

    MatchFormat format(GameModeDef mode);

    /**
     * @return null when the mode can be played with the given configuration,
     *         otherwise the reason shown in the lobby (each player reads it in their own language).
     */
    @Nullable
    Component checkAvailable(GameModeDef mode, ContentSnapshot content);

    /** Creates (but does not start) a match for exactly the given players, on the roster's map when it is one of {@link #maps}. */
    GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster);

    /** The maps a mode of this type can be played on right now: the lobby's map choice offers exactly these. */
    default List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return List.of();
    }

    /**
     * Drop-in types (an open world) have no queue: joining puts the group straight into the mode's running instance
     * ({@link GameInstance#addPlayers}), which is created on first use and stays up.
     */
    default boolean dropIn() {
        return false;
    }

    /** Whether modes of this type give players a starting kit (enables the client's loadout editor). */
    default boolean usesKit() {
        return false;
    }

    /**
     * Items the type hands to some players only (e.g. the bomb to one attacker), after the mode's kit. Players arrange
     * them in the loadout editor like the kit; the match gives them out through {@code KitService.giveRoleItem}.
     */
    default List<KitItemDef> roleItems(GameModeDef mode) {
        return List.of();
    }

    /**
     * What this type offers on this server, published to the backend at start-up for its admin UI (e.g. the
     * infection monsters), so the backend never hard-codes the mod's content. Null = nothing to publish.
     */
    @Nullable
    default JsonElement registryData() {
        return null;
    }

    /** Hook for warming caches (e.g. map files) after the configuration changed. */
    default void onContentChanged(GameServices services, ContentSnapshot content) {
    }
}
