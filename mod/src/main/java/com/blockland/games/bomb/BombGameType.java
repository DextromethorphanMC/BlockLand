package com.blockland.games.bomb;

import com.blockland.bomb.BombContent;
import com.blockland.games.common.ArenaMaps;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.KitItemDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;

/** 枪战爆破: attackers plant the bomb at a site, defenders stop them or defuse it. One round per match. */
public final class BombGameType implements GameType {
    public static final String ID = "BOMB";
    /** Where the bomb goes in a fresh layout (hotbar slot 5, as in other shooters). */
    private static final String DEFAULT_BOMB_SLOT = "4";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "枪战爆破";
    }

    @Override
    public int accentColor() {
        return 0xFFFFB300;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        BombSettings settings = BombSettings.parse(mode.settings);
        return new MatchFormat(settings.team().teamSize(), 2);
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        return maps(BombSettings.parse(mode.settings), content).isEmpty() ? Component.translatable("blockland.bomb.no_maps") : null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        BombSettings settings = BombSettings.parse(mode.settings);
        List<MapDef> maps = maps(settings, services.content().snapshot());
        if (maps.isEmpty()) throw new IllegalStateException("没有可用地图");
        MapDef map = ArenaMaps.pick(maps, roster.mapId());
        return new BombGame(services, mode, settings, map, BombSites.of(map), roster);
    }

    @Override
    public boolean usesKit() {
        return true;
    }

    /** The bomb: every player places it in their layout, the attacker who is given it finds it there. */
    @Override
    public List<KitItemDef> roleItems(GameModeDef mode) {
        KitItemDef bomb = new KitItemDef();
        bomb.item = BombContent.ITEM_KEY;
        bomb.count = 1;
        bomb.slot = DEFAULT_BOMB_SLOT;
        return List.of(bomb);
    }

    @Override
    public List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return maps(BombSettings.parse(mode.settings), content);
    }

    @Override
    public void onContentChanged(GameServices services, ContentSnapshot content) {
        for (GameModeDef mode : content.enabledModes()) {
            if (!ID.equalsIgnoreCase(mode.type)) continue;
            for (MapDef map : maps(BombSettings.parse(mode.settings), content)) services.maps().preload(map);
        }
    }

    /** Team arena maps (two spawn groups) that have at least one bomb site. */
    private static List<MapDef> maps(BombSettings settings, ContentSnapshot content) {
        return ArenaMaps.eligible(ID, settings.team(), content).stream().filter(m -> !BombSites.of(m).isEmpty()).toList();
    }
}
