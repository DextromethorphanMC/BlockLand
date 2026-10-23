package com.blockland.games.skywars;

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

/** 空岛战争. */
public final class SkyWarsGameType implements GameType {
    public static final String ID = "SKYWARS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "空岛战争";
    }

    @Override
    public int accentColor() {
        return 0xFF4FC3F7;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        SkyWarsSettings settings = SkyWarsSettings.parse(mode.settings);
        return new MatchFormat(settings.team().teamSize(), settings.team().teamCount());
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        SkyWarsSettings settings = SkyWarsSettings.parse(mode.settings);
        return ArenaMaps.eligible(ID, settings.team(), content).isEmpty() ? ArenaMaps.noMapsReason(settings.team()) : null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        SkyWarsSettings settings = SkyWarsSettings.parse(mode.settings);
        List<MapDef> maps = ArenaMaps.eligible(ID, settings.team(), services.content().snapshot());
        if (maps.isEmpty()) throw new IllegalStateException("没有可用地图");
        MapDef map = ArenaMaps.pick(maps, roster.mapId());
        return new SkyWarsGame(services, mode, settings, map, roster);
    }

    @Override
    public List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return ArenaMaps.eligible(ID, SkyWarsSettings.parse(mode.settings).team(), content);
    }

    @Override
    public void onContentChanged(GameServices services, ContentSnapshot content) {
        for (GameModeDef mode : content.enabledModes()) {
            if (!ID.equalsIgnoreCase(mode.type)) continue;
            for (MapDef map : ArenaMaps.eligible(ID, SkyWarsSettings.parse(mode.settings).team(), content)) {
                services.maps().preload(map);
            }
        }
    }
}
