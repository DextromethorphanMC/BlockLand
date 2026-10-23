package com.blockland.server.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who plays a match and on which team, as decided by matchmaking (parties are kept together). The players are still
 * in the lobby when the match is created; the game brings them into the world through {@link PlayerTransit}.
 * Team-less game types can simply use {@link #players()}.
 *
 * @param teams one list per team, in no particular order; some may be empty when a match is force-started
 * @param mapId the map the players asked for, empty = any (the game type checks it is one of the mode's maps)
 */
public record MatchRoster(List<List<Member>> teams, String mapId) {

    /** A matched player. */
    public record Member(UUID id, String name) {
    }

    public MatchRoster {
        teams = teams.stream().map(List::copyOf).toList();
        mapId = mapId == null ? "" : mapId;
    }

    public List<Member> players() {
        List<Member> all = new ArrayList<>();
        teams.forEach(all::addAll);
        return all;
    }

    public boolean isEmpty() {
        return teams.stream().allMatch(List::isEmpty);
    }
}
