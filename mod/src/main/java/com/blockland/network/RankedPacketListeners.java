package com.blockland.network;

import com.blockland.network.packet.RankedPackets;

/** Handlers for 排位 packets, kept apart so the ranked module can be wired (or left out) on its own. Client side only. */
public final class RankedPacketListeners {
    private RankedPacketListeners() {
    }

    public interface Client {
        Client NOOP = new Client() {
            @Override public void onRank(RankedPackets.S2CRank rank) { }
            @Override public void onRankChange(RankedPackets.S2CRankChange change) { }
        };

        void onRank(RankedPackets.S2CRank rank);

        void onRankChange(RankedPackets.S2CRankChange change);
    }
}
