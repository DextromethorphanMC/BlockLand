package com.blockland.server.shop;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ShopPackets;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.player.PlayerProfiles;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    public ShopRelay(MinecraftServer server, BackendClient backend, PlayerProfiles profiles) {
        this.server = server;
        this.backend = backend;
        this.profiles = profiles;
    }

    public void sendCatalogue(ServerPlayer player) {
        catalogue().whenComplete((products, error) -> server.execute(() -> {
            if (error != null) {
                BlockLand.LOGGER.warn("[BlockLand] Shop catalogue unavailable: {}", error.getMessage());
                BLNetwork.sendTo(player, new ShopPackets.S2CData(List.of(), "商店暂时无法连接，请稍后再试"));
                return;
            }
            List<ShopPackets.Product> list = products.stream().map(p -> new ShopPackets.Product(p.id, p.name,
                    nullToEmpty(p.description), nullToEmpty(p.category), p.priceCents, p.currency == null ? "CNY" : p.currency,
                    p.durationDays, nullToEmpty(p.imageVersion), profiles.entitlementUntil(player.getUUID(), p.entitlement))).toList();
            BLNetwork.sendTo(player, new ShopPackets.S2CData(list, ""));
        }));
    }

    public void purchase(ServerPlayer player, String productId) {
        PurchaseRequest request = new PurchaseRequest(player.getUUID().toString(), player.getGameProfile().getName(), productId);
        backend.<PurchaseResult>post("/api/server/shop/purchase", request, PurchaseResult.class)
                .whenComplete((result, error) -> server.execute(() -> {
                    boolean success = error == null && result != null && result.success;
                    String message = error != null ? "购买失败：无法连接商店服务" : result == null ? "购买失败" : result.message;
                    player.sendSystemMessage(Component.literal((success ? "§a" : "§c") + message));
                    BLNetwork.sendTo(player, new ShopPackets.S2CPurchaseResult(success, message));
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
