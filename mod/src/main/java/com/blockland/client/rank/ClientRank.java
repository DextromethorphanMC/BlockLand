package com.blockland.client.rank;

import com.blockland.client.result.MatchResults;
import com.blockland.network.BLNetwork;
import com.blockland.network.RankedPacketListeners;
import com.blockland.network.packet.RankedPackets;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * Client side of 排位: the player's own standing in every ranked mode as the server last sent it (each mode has a ladder
 * of its own; the lobby reads the selected mode's here) and what their last ranked match did to it — shown on the
 * results panel while it is up, otherwise as a toast (a player already back in the lobby, or one who left the match).
 */
public final class ClientRank {
    @Nullable
    private static RankedPackets.S2CRanks standings;
    @Nullable
    private static RankedPackets.S2CRankChange lastChange;
    private static long changeAt;

    private ClientRank() {
    }

    public static void init() {
        BLNetwork.setRankedClientListener(new RankedPacketListeners.Client() {
            @Override
            public void onRanks(RankedPackets.S2CRanks ranks) {
                standings = ranks;
            }

            @Override
            public void onRankChange(RankedPackets.S2CRankChange change) {
                lastChange = change;
                changeAt = System.nanoTime();
                if (!MatchResults.visible()) Minecraft.getInstance().getToastManager().addToast(new RankToast(change));
            }
        });
    }

    /**
     * The player's standing in a mode (the start standing in one not played yet), null until the server sent the
     * standings (no backend, or not yet fetched).
     */
    @Nullable
    public static RankedPackets.Standing standing(String modeId) {
        RankedPackets.S2CRanks ranks = standings;
        if (ranks == null) return null;
        for (RankedPackets.Standing s : ranks.modes()) {
            if (s.modeId().equals(modeId)) return s;
        }
        return ranks.fresh().in(modeId);
    }

    /** The last change, when it arrived at or after {@code sinceNanos} ({@link System#nanoTime}); else null. */
    @Nullable
    public static RankedPackets.S2CRankChange changeSince(long sinceNanos) {
        return lastChange != null && changeAt >= sinceNanos ? lastChange : null;
    }
}
