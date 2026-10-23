package com.blockland.games.infection;

import com.blockland.games.common.ArenaMaps;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 枪战感染: humans with guns hold out against monsters that infect whoever they kill, over a best-of series of
 * rounds. A game type of its own: it shares only the map loading and the starting kits with the arena games.
 */
public final class InfectionGameType implements GameType {
    public static final String ID = "INFECTION";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "枪战感染";
    }

    @Override
    public int accentColor() {
        return 0xFF7CB342;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        return new MatchFormat(InfectionSettings.parse(mode.settings).players(), 1);
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        InfectionSettings settings = InfectionSettings.parse(mode.settings);
        if (MonsterCatalog.get().offered(settings.monsters()).isEmpty()) return Component.translatable("blockland.infection.no_monsters");
        return maps(settings, content).isEmpty() ? Component.translatable("blockland.infection.no_maps") : null;
    }

    @Override
    public List<MapDef> maps(GameModeDef mode, ContentSnapshot content) {
        return maps(InfectionSettings.parse(mode.settings), content);
    }

    /** Maps with a usable file and at least one spawn point (role spawns or team spawns). */
    private static List<MapDef> maps(InfectionSettings settings, ContentSnapshot content) {
        return ArenaMaps.candidates(ID, settings.mapIds(), content).stream().filter(m -> !InfectionSpawns.of(m).isEmpty()).toList();
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        InfectionSettings settings = InfectionSettings.parse(mode.settings);
        List<MapDef> maps = maps(settings, services.content().snapshot());
        if (maps.isEmpty()) throw new IllegalStateException("没有可用地图");
        MapDef map = ArenaMaps.pick(maps, roster.mapId());
        return new InfectionGame(services, mode, settings, map, roster);
    }

    /** Humans get the mode's kit, in each player's own arrangement. */
    @Override
    public boolean usesKit() {
        return true;
    }

    /** The monsters this server offers, for the admin's mode form. */
    @Override
    public JsonElement registryData() {
        return MonsterCatalog.get().registry();
    }

    @Override
    public void onContentChanged(GameServices services, ContentSnapshot content) {
        for (GameModeDef mode : content.enabledModes()) {
            if (!ID.equalsIgnoreCase(mode.type)) continue;
            for (MapDef map : maps(InfectionSettings.parse(mode.settings), content)) services.maps().preload(map);
        }
    }
}
