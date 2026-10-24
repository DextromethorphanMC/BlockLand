package com.blockland.network;

import com.blockland.network.packet.ViewmodelPackets;

/** Handler for the watched player's hand actions (spectating in first person); implemented by the client viewmodel facade. */
public final class ViewmodelPacketListeners {
    private ViewmodelPacketListeners() {
    }

    public interface Client {
        Client NOOP = action -> { };

        void onAction(ViewmodelPackets.S2CAction action);
    }
}
