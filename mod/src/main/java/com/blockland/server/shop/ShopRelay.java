package com.blockland.server.shop;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.ShopPackets;
import com.blockland.network.packet.TextJson;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.session.PlayerDirectory;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The in-game shop on the server: relays catalogue and purchases between the client
 * and the backend (clients never talk to the backend directly). What a purchase unlocks is an
 * entitlement in {@link PlayerProfiles}; this class does not know what entitlements do.
 */
public final class ShopRelay {
    private static final long CATALOGUE_TTL_MS = 30_000;
    private static final Type PRODUCT_LIST = new TypeToken<List<ProductJson>>() { }.getType();

    private final MinecraftServer server;
    private final BackendClient backend;
    private final PlayerProfiles profiles;
    private final PlayerDirectory players;
    /** The inventory icon of an entitlement key that is an item, "" otherwise (shown when a product has no image). */
    private Function<String, String> itemIcon = key -> "";
    private volatile CompletableFuture<List<ProductJson>> catalogue;
    private volatile long catalogueFetchedAt;

    /** Mirrors the backend's {@code Product}. */
    static final class ProductJson {
        String id;
        String name;
        String description;
        String category;
        int priceCents;
        String currency;
        String entitlement;
        int durationDays;
        String imageVersion;
    }

    static final class PurchaseResult {
        boolean success;
        String message;
    }

    private record PurchaseRequest(String uuid, String name, String productId) {
    }

    public ShopRelay(MinecraftServer server, BackendClient backend, PlayerProfiles profiles, PlayerDirectory players) {
        this.server = server;
        this.backend = backend;
        this.profiles = profiles;
        this.players = players;
    }

    /** How an entitlement that is an inventory item looks (its icon), so products without an image can show it. */
    public void setItemIcons(Function<String, String> itemIcon) {
        this.itemIcon = itemIcon;
    }

    public void sendCatalogue(UUID player) {
        catalogue().whenComplete((products, error) -> server.execute(() -> {
            if (error != null) {
                BlockLand.LOGGER.warn("[BlockLand] Shop catalogue unavailable: {}", error.getMessage());
                BLNetwork.sendTo(player, new ShopPackets.S2CData(List.of(), "blockland.shop.unreachable"));
                return;
            }
            List<ShopPackets.Product> list = products.stream().map(p -> new ShopPackets.Product(p.id, p.name,
                    nullToEmpty(p.description), nullToEmpty(p.category), p.priceCents, p.currency == null ? "CNY" : p.currency,
                    p.durationDays, nullToEmpty(p.imageVersion), profiles.entitlementUntil(player, p.entitlement),
                    p.entitlement == null ? "" : nullToEmpty(itemIcon.apply(p.entitlement)))).toList();
            BLNetwork.sendTo(player, new ShopPackets.S2CData(list, ""));
        }));
    }

    public void purchase(UUID player, String productId) {
        String name = players.name(player);
        if (name == null) return;
        PurchaseRequest request = new PurchaseRequest(player.toString(), name, productId);
        backend.<PurchaseResult>post("/api/server/shop/purchase", request, PurchaseResult.class)
                .whenComplete((result, error) -> server.execute(() -> {
                    boolean success = error == null && result != null && result.success;
                    Component message = error != null ? Component.translatable("blockland.shop.purchase_unreachable")
                            : result == null ? Component.translatable("blockland.shop.purchase_failed") : Component.literal(result.message);
                    players.notify(player, success ? LobbyPackets.Level.SUCCESS : LobbyPackets.Level.ERROR, message);
                    BLNetwork.sendTo(player, new ShopPackets.S2CPurchaseResult(success, TextJson.write(message)));
                    if (success) profiles.refresh(player).thenRun(() -> sendCatalogue(player));
                }));
    }

    private CompletableFuture<List<ProductJson>> catalogue() {
        CompletableFuture<List<ProductJson>> current = catalogue;
        long now = System.currentTimeMillis();
        if (current == null || now - catalogueFetchedAt > CATALOGUE_TTL_MS || current.isCompletedExceptionally()) {
            catalogueFetchedAt = now;
            current = backend.get("/api/server/shop/products", PRODUCT_LIST);
            catalogue = current;
        }
        return current;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
