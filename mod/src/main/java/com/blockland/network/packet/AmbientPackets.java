package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/** The world's ambient props a match's map asks for (rendering spec §29). */
public final class AmbientPackets {
    private AmbientPackets() {
    }

    /** {@link S2CAmbient#effects}: tumbleweeds roll over the match's open ground. */
    public static final int TUMBLEWEEDS = 1;

    /**
     * To a player in the world: the ambient props of the match they are in, inside its bounds (world coordinates); no
     * effects ({@link #NONE}) when they are in no match or its map asks for none.
     */
    public record S2CAmbient(int effects, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static final S2CAmbient NONE = new S2CAmbient(0, 0, 0, 0, 0, 0, 0);

        public boolean has(int effect) {
            return (effects & effect) != 0;
        }

        public static void encode(S2CAmbient msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.effects);
            if (msg.effects == 0) return;
            buf.writeDouble(msg.minX);
            buf.writeDouble(msg.minY);
            buf.writeDouble(msg.minZ);
            buf.writeDouble(msg.maxX);
            buf.writeDouble(msg.maxY);
            buf.writeDouble(msg.maxZ);
        }

        public static S2CAmbient decode(FriendlyByteBuf buf) {
            int effects = buf.readVarInt();
            if (effects == 0) return NONE;
            return new S2CAmbient(effects, buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble());
        }
    }
}
