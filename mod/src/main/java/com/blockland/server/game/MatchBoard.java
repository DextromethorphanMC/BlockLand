package com.blockland.server.game;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The Tab board as a game describes it: what is being played, how it stands, and the sides with their players. Neutral
 * data like {@link MatchResult}: the game decides the teams and any extra columns, the kill tracker adds pings and
 * turns it into the board packet. Texts are components, so every player reads them in their own language.
 *
 * @param title    the game ("快速PVP")
 * @param subtitle mode and map
 * @param status   time left, round, phase (empty = none)
 * @param columns  extra column titles after kills and deaths, matching every row's {@code values}
 */
public record MatchBoard(Component title, Component subtitle, Component status, List<Component> columns, List<Team> teams) {

    public enum State { ALIVE, DEAD, SPECTATING }

    /**
     * @param color ARGB of the side
     * @param info  e.g. "存活 3/4" (empty = none)
     */
    public record Team(Component name, int color, Component info, List<Row> rows) {
        public Team {
            rows = List.copyOf(rows);
        }
    }

    /** @param state null = from the player (a spectator is spectating, everyone else alive) */
    public record Row(UUID player, String name, int kills, int deaths, List<Component> values, @Nullable State state) {
        public Row {
            values = List.copyOf(values);
        }
    }

    /** What the kill tracker knows about everyone, for boards that do not count themselves. */
    public interface Stats {
        String name(UUID player);

        int kills(UUID player);

        int deaths(UUID player);
    }

    public MatchBoard {
        columns = List.copyOf(columns);
        teams = List.copyOf(teams);
    }
}
