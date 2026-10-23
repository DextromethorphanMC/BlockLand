package com.blockland.games.practice.aim;

import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.MatchRoster;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/** 枪械瞄准练习: single player, every gun with endless ammo, fake players on a shooting range. Builds its own course. */
public final class AimPracticeGameType implements GameType {
    public static final String ID = "AIM_PRACTICE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "枪械瞄准练习";
    }

    @Override
    public int accentColor() {
        return 0xFFFFA726;
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
        return new AimPracticeGame(services, mode, AimPracticeSettings.parse(mode.settings), roster);
    }
}
