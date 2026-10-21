package com.blockland.server.game;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Who plays a match and on which team, as decided by matchmaking (parties are kept together).
 * Team-less game types can simply use {@link #players()}.
 *
 * @param teams one list per team, in no particular order; some may be empty when a match is force-started
 */
public record MatchRoster(List<List<ServerPlayer>> teams) {
    public MatchRoster {
        teams = teams.stream().map(List::copyOf).toList();
    }

    public List<ServerPlayer> players() {
        List<ServerPlayer> all = new ArrayList<>();
        teams.forEach(all::addAll);
        return all;
    }

    public boolean isEmpty() {
        return teams.stream().allMatch(List::isEmpty);
    }
}
