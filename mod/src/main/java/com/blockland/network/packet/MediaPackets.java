package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Images from the backend media store (shop products, mode covers, group covers). The client asks for
 * an image it has not cached; the server relays the PNG so clients never contact the backend themselves.
 */
public final class MediaPackets {
    public static final String SHOP = "shop";
    public static final String MODE = "mode";
    public static final String GROUP = "group";
    public static final String MAP = "map";

    private MediaPackets() {
    }

    public record C2SRequest(String namespace, String id, String version) {
        public static void encode(C2SRequest msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.namespace, 16);
            buf.writeUtf(msg.id, 64);
            buf.writeUtf(msg.version, 64);
        }

        public static C2SRequest decode(FriendlyByteBuf buf) {
            return new C2SRequest(buf.readUtf(16), buf.readUtf(64), buf.readUtf(64));
        }
    }

    public record S2CImage(String namespace, String id, String version, byte[] png) {
        private static final int MAX_BYTES = 768 * 1024;

        public static void encode(S2CImage msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.namespace, 16);
            buf.writeUtf(msg.id, 64);
            buf.writeUtf(msg.version, 64);
            buf.writeByteArray(msg.png);
        }

        public static S2CImage decode(FriendlyByteBuf buf) {
            return new S2CImage(buf.readUtf(16), buf.readUtf(64), buf.readUtf(64), buf.readByteArray(MAX_BYTES));
        }
    }
}
