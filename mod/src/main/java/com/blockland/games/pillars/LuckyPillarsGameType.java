package com.blockland.games.pillars;

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

import javax.annotation.Nullable;
import java.util.List;

/** 幸运高柱: everyone starts on top of a pillar of their own, random items arrive over time, the last one standing wins. */
public final class LuckyPillarsGameType implements GameType {
    public static final String ID = "LUCKY_PILLARS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "幸运高柱";
    }

    @Override
    public int accentColor() {
        return 0xFFFFB300;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        LuckyPillarsSettings settings = LuckyPillarsSettings.parse(mode.settings);
        return new MatchFormat(1, settings.team().teamCount());
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        LuckyPillarsSettings settings = LuckyPillarsSettings.parse(mode.settings);
        return ArenaMaps.eligible(ID, settings.team(), content).isEmpty()
                ? Component.translatable("blockland.pillars.no_maps", settings.team().teamCount()) : null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        LuckyPillarsSettings settings = LuckyPillarsSettings.parse(mode.settings);
        List<MapDef> maps = ArenaMaps.eligible(ID, settings.team(), services.content().snapshot());
        if (maps.isEmpty()) throw new IllegalStateException("没有可用地图");
        return new LuckyPillarsGame(services, mode, settings, ArenaMaps.pick(maps, roster.mapId()), roster);
    }

    @Override
    public List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return ArenaMaps.eligible(ID, LuckyPillarsSettings.parse(mode.settings).team(), content);
    }

    @Override
    public void onContentChanged(GameServices services, ContentSnapshot content) {
        for (GameModeDef mode : content.enabledModes()) {
            if (!ID.equalsIgnoreCase(mode.type)) continue;
            for (MapDef map : maps(mode, content)) services.maps().preload(map);
        }
    }
}
