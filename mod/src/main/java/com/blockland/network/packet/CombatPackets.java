package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kill feedback and the match board: the kill notice for the killer (medals), the kill feed line for everyone in the
 * same match, and the Tab board while a player holds the key. Weapons travel as item ids (the client shows the item's
 * name), texts the board's game decides as component JSON ({@link TextJson}), so everyone reads their own language.
 */
public final class CombatPackets {
    private CombatPackets() {
    }

    /** The player started (or stopped) holding the board key: the server sends the board while it is held. */
    public record C2SBoard(boolean open) {
        public static void encode(C2SBoard msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.open);
        }

        public static C2SBoard decode(FriendlyByteBuf buf) {
            return new C2SBoard(buf.readBoolean());
        }
    }

    /**
     * You killed something.
     *
     * @param weapon item id of what made the kill ("" = unknown)
     * @param streak kills in a row without dying, this one included
     * @param player the victim was a player (not a practice target or a creature)
     */
    public record S2CKillNotice(String victim, String weapon, boolean headshot, int streak, boolean player) {
        public static void encode(S2CKillNotice msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.victim, 64);
            buf.writeUtf(msg.weapon, 128);
            buf.writeBoolean(msg.headshot);
            buf.writeVarInt(msg.streak);
            buf.writeBoolean(msg.player);
        }

        public static S2CKillNotice decode(FriendlyByteBuf buf) {
            return new S2CKillNotice(buf.readUtf(64), buf.readUtf(128), buf.readBoolean(), buf.readVarInt(), buf.readBoolean());
        }
    }

    /**
     * One line of the kill feed.
     *
     * @param killer      "" when nobody gets the kill (fall, void)
     * @param killerColor RGB of the killer's team colour, likewise {@code victimColor}
     */
    public record S2CKillFeed(String killer, int killerColor, String victim, int victimColor, String weapon, boolean headshot) {
        public static void encode(S2CKillFeed msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.killer, 64);
            buf.writeInt(msg.killerColor);
            buf.writeUtf(msg.victim, 64);
            buf.writeInt(msg.victimColor);
            buf.writeUtf(msg.weapon, 128);
            buf.writeBoolean(msg.headshot);
        }

        public static S2CKillFeed decode(FriendlyByteBuf buf) {
            return new S2CKillFeed(buf.readUtf(64), buf.readInt(), buf.readUtf(64), buf.readInt(), buf.readUtf(128), buf.readBoolean());
        }
    }

    /** How a player on the board is doing. */
    public enum State { ALIVE, DEAD, SPECTATING }

    /**
     * The Tab board.
     *
     * @param title    game or mode name
     * @param subtitle mode and map
     * @param status   time left, round, phase (empty = none)
     * @param columns  extra column titles after kills / deaths, matching every row's {@code values}
     */
    public record S2CBoard(Component title, Component subtitle, Component status, List<Component> columns, List<Team> teams) {
        /** @param color ARGB; {@code info} e.g. "存活 3/4" */
        public record Team(Component name, int color, Component info, List<Row> rows) {
        }

        /** @param ping milliseconds, -1 = unknown */
        public record Row(UUID player, String name, int kills, int deaths, List<Component> values, int ping, State state) {
        }

        public static void encode(S2CBoard msg, FriendlyByteBuf buf) {
            buf.writeUtf(TextJson.write(msg.title));
            buf.writeUtf(TextJson.write(msg.subtitle));
            buf.writeUtf(TextJson.write(msg.status));
            buf.writeVarInt(msg.columns.size());
            for (Component column : msg.columns) buf.writeUtf(TextJson.write(column));
            buf.writeVarInt(msg.teams.size());
            for (Team team : msg.teams) {
                buf.writeUtf(TextJson.write(team.name));
                buf.writeInt(team.color);
                buf.writeUtf(TextJson.write(team.info));
                buf.writeVarInt(team.rows.size());
                for (Row row : team.rows) {
                    buf.writeUUID(row.player);
                    buf.writeUtf(row.name, 64);
                    buf.writeVarInt(row.kills);
                    buf.writeVarInt(row.deaths);
                    buf.writeVarInt(row.values.size());
                    for (Component value : row.values) buf.writeUtf(TextJson.write(value));
                    buf.writeVarInt(row.ping);
                    buf.writeEnum(row.state);
                }
            }
        }

        public static S2CBoard decode(FriendlyByteBuf buf) {
            Component title = TextJson.read(buf.readUtf()), subtitle = TextJson.read(buf.readUtf()), status = TextJson.read(buf.readUtf());
            int columnCount = Math.min(buf.readVarInt(), 16);
            List<Component> columns = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) columns.add(TextJson.read(buf.readUtf()));
            int teamCount = Math.min(buf.readVarInt(), 64);
            List<Team> teams = new ArrayList<>(teamCount);
            for (int t = 0; t < teamCount; t++) {
                Component name = TextJson.read(buf.readUtf());
                int color = buf.readInt();
                Component info = TextJson.read(buf.readUtf());
                int rowCount = Math.min(buf.readVarInt(), 256);
                List<Row> rows = new ArrayList<>(rowCount);
                for (int r = 0; r < rowCount; r++) {
                    UUID id = buf.readUUID();
                    String player = buf.readUtf(64);
                    int kills = buf.readVarInt(), deaths = buf.readVarInt();
                    int valueCount = Math.min(buf.readVarInt(), 16);
                    List<Component> values = new ArrayList<>(valueCount);
                    for (int v = 0; v < valueCount; v++) values.add(TextJson.read(buf.readUtf()));
                    rows.add(new Row(id, player, kills, deaths, values, buf.readVarInt(), buf.readEnum(State.class)));
                }
                teams.add(new Team(name, color, info, rows));
            }
            return new S2CBoard(title, subtitle, status, columns, teams);
        }
    }
}
