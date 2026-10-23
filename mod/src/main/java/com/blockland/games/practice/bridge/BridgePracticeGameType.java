package com.blockland.games.practice.bridge;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/** 搭路练习: single player, endless blocks, bridge across the void to the goal island. Builds its own course. */
public final class BridgePracticeGameType implements GameType {
    public static final String ID = "BRIDGE_PRACTICE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "搭路练习";
    }

    @Override
    public int accentColor() {
        return 0xFF8BC34A;
    }

    @Override
    public MatchFormat format(GameModeDef mode) {
        return new MatchFormat(1, 1);
    }

    @Nullable
    @Override
    public Component checkAvailable(GameModeDef mode, ContentSnapshot content) {
        return null;
    }

    @Override
    public GameInstance create(GameServices services, GameModeDef mode, MatchRoster roster) {
        return new BridgePracticeGame(services, mode, BridgePracticeSettings.parse(mode.settings), roster);
    }
}
