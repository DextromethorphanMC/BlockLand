package com.blockland.games.battleroyale;

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

/** 大逃杀: everyone jumps from a plane over an island, loots and fights; the last one standing wins. */
public final class BattleRoyaleGameType implements GameType {
    public static final String ID = "BATTLE_ROYALE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "大逃杀";
    }

    @Override
    public int accentColor() {
        return 0xFF4FC3F7;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        return new MatchFormat(1, BattleRoyaleSettings.parse(mode.settings).players());
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        BattleRoyaleSettings settings = BattleRoyaleSettings.parse(mode.settings);
        if (settings.loot().isEmpty()) return Component.translatable("blockland.br.no_loot");
        return maps(settings, content).isEmpty() ? Component.translatable("blockland.br.no_maps") : null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        BattleRoyaleSettings settings = BattleRoyaleSettings.parse(mode.settings);
        List<MapDef> maps = maps(settings, services.content().snapshot());
        if (maps.isEmpty()) throw new IllegalStateException("没有可用地图");
        return new BattleRoyaleGame(services, mode, settings, ArenaMaps.pick(maps, roster.mapId()), roster);
    }

    @Override
    public boolean usesKit() {
        return true;
    }

    @Override
    public List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return maps(BattleRoyaleSettings.parse(mode.settings), content);
    }

    @Override
    public void onContentChanged(GameServices services, ContentSnapshot content) {
        for (GameModeDef mode : content.enabledModes()) {
            if (!ID.equalsIgnoreCase(mode.type)) continue;
            for (MapDef map : maps(BattleRoyaleSettings.parse(mode.settings), content)) services.maps().preload(map);
        }
    }

    /** Maps of the type with a usable file (team spawns are not needed: everyone comes from the plane). */
    private static List<MapDef> maps(BattleRoyaleSettings settings, ContentSnapshot content) {
        return ArenaMaps.candidates(ID, settings.mapIds(), content);
    }
}
