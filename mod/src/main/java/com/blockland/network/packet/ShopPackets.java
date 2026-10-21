package com.blockland.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** Packets of the in-game shop (lobby "商店" tab). */
public final class ShopPackets {
    private ShopPackets() {
    }

    /** Client opens the shop tab. */
    public record C2SRequest() {
        public static final C2SRequest INSTANCE = new C2SRequest();

        public static void encode(C2SRequest msg, FriendlyByteBuf buf) {
        }

        public static C2SRequest decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }
    }

    /** Client confirmed a purchase. */
    public record C2SPurchase(String productId) {
        public static void encode(C2SPurchase msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.productId, 64);
        }

        public static C2SPurchase decode(FriendlyByteBuf buf) {
            return new C2SPurchase(buf.readUtf(64));
        }
    }

    /**
     * @param imageVersion empty when the product has no image
     * @param ownedUntil   -1 = not owned, 0 = owned permanently, otherwise epoch millis it expires
     */
    public record Product(String id, String name, String description, String category, int priceCents, String currency,
                          int durationDays, String imageVersion, long ownedUntil) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(id, 64);
            buf.writeUtf(name);
            buf.writeUtf(description);
            buf.writeUtf(category);
            buf.writeVarInt(priceCents);
            buf.writeUtf(currency, 8);
            buf.writeVarInt(durationDays);
            buf.writeUtf(imageVersion, 64);
            buf.writeLong(ownedUntil);
        }

        static Product decode(FriendlyByteBuf buf) {
            return new Product(buf.readUtf(64), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                    buf.readUtf(8), buf.readVarInt(), buf.readUtf(64), buf.readLong());
        }
    }

    /** @param error empty unless the catalogue could not be loaded */
    public record S2CData(List<Product> products, String error) {
        public static void encode(S2CData msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.products.size());
            for (Product p : msg.products) p.encode(buf);
            buf.writeUtf(msg.error);
        }

        public static S2CData decode(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<Product> products = new ArrayList<>(size);
            for (int i = 0; i < size; i++) products.add(Product.decode(buf));
            return new S2CData(products, buf.readUtf());
        }
    }

    /** Result of a purchase, shown in the shop. */
    public record S2CPurchaseResult(boolean success, String message) {
        public static void encode(S2CPurchaseResult msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.success);
            buf.writeUtf(msg.message);
        }

        public static S2CPurchaseResult decode(FriendlyByteBuf buf) {
            return new S2CPurchaseResult(buf.readBoolean(), buf.readUtf());
        }
    }
}
