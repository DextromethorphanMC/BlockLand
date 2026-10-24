package com.blockland.client.rank;

import com.blockland.client.result.MatchResults;
import com.blockland.network.BLNetwork;
import com.blockland.network.RankedPacketListeners;
import com.blockland.network.packet.RankedPackets;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;

/**
 * Client side of 排位: the player's own standing as the server last sent it (lobby, results, the profile and loading
 * screens read it here) and what their last ranked match did to it — shown on the results panel while it is up,
 * otherwise as a toast (a player already back in the lobby, or one who left the match).
 */
public final class ClientRank {
    @Nullable
    private static RankedPackets.S2CRank standing;
    @Nullable
    private static RankedPackets.S2CRankChange lastChange;
    private static long changeAt;

    private ClientRank() {
    }

    public static void init() {
        BLNetwork.setRankedClientListener(new RankedPacketListeners.Client() {
            @Override
            public void onRank(RankedPackets.S2CRank rank) {
                standing = rank;
            }

            @Override
            public void onRankChange(RankedPackets.S2CRankChange change) {
                lastChange = change;
                changeAt = System.nanoTime();
                if (!MatchResults.visible()) Minecraft.getInstance().getToastManager().addToast(new RankToast(change));
            }
        });
    }

    /** The player's standing, null until the server sent it (no backend, or not yet fetched). */
    @Nullable
    public static RankedPackets.S2CRank standing() {
        return standing;
    }

    /** The last change, when it arrived at or after {@code sinceNanos} ({@link System#nanoTime}); else null. */
    @Nullable
    public static RankedPackets.S2CRankChange changeSince(long sinceNanos) {
        return lastChange != null && changeAt >= sinceNanos ? lastChange : null;
    }
}
