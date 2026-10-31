package com.blockland.games.uhc;

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

/** 极限生存 (UHC): a fresh survival world inside a border, everyone for themselves, the last one alive wins. */
public final class UhcGameType implements GameType {
    public static final String ID = "UHC";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "极限生存";
    }

    @Override
    public int accentColor() {
        return 0xFFE5533D;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        return new MatchFormat(1, UhcSettings.parse(mode.settings).players());
    }

    /** No maps: the world is generated; it only needs the mod's UHC dimension. */
    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        return null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        ServerLevel level = services.server().getLevel(UhcRegions.DIMENSION);
        if (level == null) throw new IllegalStateException("维度不存在: " + UhcRegions.DIMENSION.location());
        return new UhcGame(services, mode, UhcSettings.parse(mode.settings), level, roster);
    }

    /** The kit (empty by default) is what everyone starts with. */
    @Override
    public boolean usesKit() {
        return true;
    }
}
