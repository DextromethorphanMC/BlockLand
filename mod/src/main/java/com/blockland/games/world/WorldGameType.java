package com.blockland.games.world;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/** 开放世界: a generated world players walk into from the lobby whenever they like, no queue, no end. */
public final class WorldGameType implements GameType {
    public static final String ID = "WORLD";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "开放世界";
    }

    @Override
    public int accentColor() {
        return 0xFF66BB6A;
    }

    /** One side as big as the world takes: the lobby shows who is in it rather than a queue. */
    @Override
    public MatchFormat format(GameModeDef mode) {
        return new MatchFormat(WorldSettings.parse(mode.settings).maxPlayers(), 1);
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        return WorldSettings.parse(mode.settings).dimension() == null ? Component.translatable("blockland.world.bad_dimension") : null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        WorldSettings settings = WorldSettings.parse(mode.settings);
        ServerLevel level = services.server().getLevel(WorldGame.dimensionKey(settings));
        if (level == null) throw new IllegalStateException("维度不存在: " + settings.dimension());
        return new WorldGame(services, mode, settings, level, roster);
    }

    @Override
    public boolean dropIn() {
        return true;
    }

    /** The kit is the starting equipment of a first visit. */
    @Override
    public boolean usesKit() {
        return true;
    }
}
