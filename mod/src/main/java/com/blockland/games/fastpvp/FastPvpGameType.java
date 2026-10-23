package com.blockland.games.fastpvp;

import com.blockland.games.common.ArenaMaps;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;

/** 快速PVP: fixed kit, custom arena map, last team standing. */
public final class FastPvpGameType implements GameType {
    public static final String ID = "FASTPVP";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "快速PVP";
    }

    @Override
    public int accentColor() {
        return 0xFFEF5350;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        FastPvpSettings settings = FastPvpSettings.parse(mode.settings);
        return new MatchFormat(settings.team().teamSize(), settings.team().teamCount());
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        FastPvpSettings settings = FastPvpSettings.parse(mode.settings);
        return ArenaMaps.eligible(ID, settings.team(), content).isEmpty() ? ArenaMaps.noMapsReason(settings.team()) : null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        FastPvpSettings settings = FastPvpSettings.parse(mode.settings);
        List<MapDef> maps = ArenaMaps.eligible(ID, settings.team(), services.content().snapshot());
        if (maps.isEmpty()) throw new IllegalStateException("没有可用地图");
        MapDef map = ArenaMaps.pick(maps, roster.mapId());
        return new FastPvpGame(services, mode, settings, map, roster);
    }

    @Override
    public boolean usesKit() {
        return true;
    }

    @Override
    public List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return ArenaMaps.eligible(ID, FastPvpSettings.parse(mode.settings).team(), content);
    }

    @Override
    public void onContentChanged(GameServices services, ContentSnapshot content) {
        for (GameModeDef mode : content.enabledModes()) {
            if (!ID.equalsIgnoreCase(mode.type)) continue;
            for (MapDef map : ArenaMaps.eligible(ID, FastPvpSettings.parse(mode.settings).team(), content)) {
                services.maps().preload(map);
            }
        }
    }
}
