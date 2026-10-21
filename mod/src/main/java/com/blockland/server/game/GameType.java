package com.blockland.server.game;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
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
     *         otherwise a human readable reason shown in the lobby.
     */
    @Nullable
    String checkAvailable(GameModeDef mode, ContentSnapshot content);

    /** Creates (but does not start) a match for exactly the given players. */
    GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster);

    /** Whether modes of this type give players a starting kit (enables the client's loadout editor). */
    default boolean usesKit() {
        return false;
    }

    /** Hook for warming caches (e.g. map files) after the configuration changed. */
    default void onContentChanged(GameServices services, ContentSnapshot content) {
    }
}
