package com.blockland.server.game;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.S2CMatchResult;
import com.blockland.network.packet.TextJson;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends a finished match's {@link MatchResult} to its participants as their results panel: each player gets their
 * own outcome and has their own row marked. Games only describe the result; they never build packets.
 */
public final class MatchResultPublisher {
    private final MinecraftServer server;

    public MatchResultPublisher(MinecraftServer server) {
        this.server = server;
    }

    /**
     * @param endingSeconds how long the match keeps running after its result: the panel's countdown ends exactly when
     *                      the player is sent back to the lobby
     */
    public void publish(MatchResult result, int endingSeconds) {
        int show = Math.max(1, endingSeconds);
        for (MatchResult.Row recipient : result.rows()) {
            ServerPlayer player = server.getPlayerList().getPlayer(recipient.player());
            if (player == null) continue;
            List<S2CMatchResult.Row> rows = new ArrayList<>();
            for (MatchResult.Row row : result.rows()) {
                rows.add(new S2CMatchResult.Row(row.name(), TextJson.write(row.team()), row.teamColor(), texts(row.values()),
                        row.player().equals(recipient.player()), row.outcome() == MatchResult.Outcome.VICTORY, row.mvp()));
            }
            int outcome = switch (recipient.outcome()) {
                case VICTORY -> S2CMatchResult.VICTORY;
                case DEFEAT -> S2CMatchResult.DEFEAT;
                case DRAW -> S2CMatchResult.DRAW;
            };
            // texts go as component JSON: each player reads them in their own language
            BLNetwork.sendTo(player, new S2CMatchResult(result.modeId(), result.modeTitle(), TextJson.write(result.gameName()),
                    TextJson.write(result.headline()), result.reason() == null ? "" : TextJson.write(result.reason()), outcome,
                    result.durationSeconds(), show, texts(result.columns()), rows));
        }
    }

    private static List<String> texts(List<Component> components) {
        return components.stream().map(TextJson::write).toList();
    }
}
