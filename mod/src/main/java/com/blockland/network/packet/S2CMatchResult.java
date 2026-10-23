package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's results panel at the end of a match. Everything shown comes from the server; the game name, headline,
 * reason, column titles, team labels and values are component JSON ({@link TextJson}) so they show in the player's
 * language.
 *
 * @param modeId      the mode that was played (for the client's saved kit layout when playing again)
 * @param outcome     0 = victory, 1 = defeat, 2 = draw (for the receiving player)
 * @param showSeconds seconds until the server sends the player back to the lobby (the panel closes then)
 */
public record S2CMatchResult(String modeId, String modeTitle, String gameName, String headline, String reason, int outcome,
                             int durationSeconds, int showSeconds, List<String> columns, List<Row> rows) {
    public static final int VICTORY = 0, DEFEAT = 1, DRAW = 2;
    private static final int MAX_ROWS = 64;
    /** Longest component JSON of a headline / reason, and of a column title, team label or value. */
    private static final int TEXT = 2048, CELL = 512;

    /**
     * @param self   the receiving player's own row
     * @param winner on the winning side
     */
    public record Row(String name, String team, int teamColor, List<String> values, boolean self, boolean winner, boolean mvp) {
    }

    public static void encode(S2CMatchResult msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modeId, 64);
        buf.writeUtf(msg.modeTitle, 128);
        buf.writeUtf(msg.gameName, TEXT);
        buf.writeUtf(msg.headline, TEXT);
        buf.writeUtf(msg.reason, TEXT);
        buf.writeVarInt(msg.outcome);
        buf.writeVarInt(msg.durationSeconds);
        buf.writeVarInt(msg.showSeconds);
        buf.writeVarInt(msg.columns.size());
        for (String column : msg.columns) buf.writeUtf(column, CELL);
        int rows = Math.min(MAX_ROWS, msg.rows.size());
        buf.writeVarInt(rows);
        for (int i = 0; i < rows; i++) {
            Row row = msg.rows.get(i);
            buf.writeUtf(row.name, 32);
            buf.writeUtf(row.team, CELL);
            buf.writeInt(row.teamColor);
            buf.writeVarInt(row.values.size());
            for (String value : row.values) buf.writeUtf(value, CELL);
            buf.writeBoolean(row.self);
            buf.writeBoolean(row.winner);
            buf.writeBoolean(row.mvp);
        }
    }

    public static S2CMatchResult decode(FriendlyByteBuf buf) {
        String modeId = buf.readUtf(64);
        String modeTitle = buf.readUtf(128), gameName = buf.readUtf(TEXT), headline = buf.readUtf(TEXT), reason = buf.readUtf(TEXT);
        int outcome = buf.readVarInt(), duration = buf.readVarInt(), show = buf.readVarInt();
        int columnCount = Math.min(16, buf.readVarInt());
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) columns.add(buf.readUtf(CELL));
        int rowCount = Math.min(MAX_ROWS, buf.readVarInt());
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            String name = buf.readUtf(32), team = buf.readUtf(CELL);
            int color = buf.readInt();
            int valueCount = Math.min(16, buf.readVarInt());
            List<String> values = new ArrayList<>();
            for (int v = 0; v < valueCount; v++) values.add(buf.readUtf(CELL));
            rows.add(new Row(name, team, color, values, buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        }
        return new S2CMatchResult(modeId, modeTitle, gameName, headline, reason, outcome, duration, show, columns, rows);
    }
}
