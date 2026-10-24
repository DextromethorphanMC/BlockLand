package com.blockland.server.game;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.profile.Cosmetics;
import com.blockland.server.ranked.Ranks;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells the players of a match that was just created (or a drop-in world they join) what their loading screen shows:
 * the mode and its type, the map the game picked, their own side with everyone's name card and rank, and how many play
 * against them. Generic: sides come from the {@link MatchRoster} (free-for-all = just the player).
 */
final class MatchIntro {
    private MatchIntro() {
    }

    /**
     * @param joining the players to tell (the whole roster for a new match, the group for a drop-in)
     * @param players everyone in the match afterwards
     */
    static void send(GameType type, GameModeDef mode, GameInstance game, MatchRoster joining, int players, boolean dropIn) {
        MapDef map = game.map();
        boolean ranked = mode.flag("ranked", false);
        String title = mode.title == null ? mode.id : mode.title;
        for (List<MatchRoster.Member> side : joining.teams()) {
            for (MatchRoster.Member self : side) {
                List<S2CMatchFound.Member> team = new ArrayList<>();
                team.add(member(self));
                for (MatchRoster.Member other : side) {
                    if (!other.id().equals(self.id())) team.add(member(other));
                }
                int opponents = dropIn ? 0 : Math.max(0, players - side.size());
                BLNetwork.sendTo(self.id(), new S2CMatchFound(mode.id, title, type.displayName(), type.accentColor(),
                        map == null ? "" : map.id, map == null || map.name == null ? "" : map.name,
                        map == null || map.coverVersion == null ? "" : map.coverVersion, dropIn, players, opponents, ranked, team));
            }
        }
    }

    private static S2CMatchFound.Member member(MatchRoster.Member member) {
        String card = Cosmetics.get().get(member.id(), Cosmetics.CARD);
        if (card == null) card = "";
        if (card.startsWith("card:")) card = card.substring("card:".length());
        Ranks.RankInfo rank = Ranks.get().rankOf(member.id());
        return new S2CMatchFound.Member(member.id(), member.name(), card, rank.tier().id(), rank.score(), rank.position() > 0 || rank.matches() > 0);
    }
}
