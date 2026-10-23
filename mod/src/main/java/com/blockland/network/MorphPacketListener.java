package com.blockland.network;

import com.blockland.network.packet.S2CMorph;

/** Client handler for player morphs. Not tied to a game type: any game may draw a player as another entity. */
public interface MorphPacketListener {
    MorphPacketListener NOOP = morph -> { };

    void onMorph(S2CMorph morph);
}
