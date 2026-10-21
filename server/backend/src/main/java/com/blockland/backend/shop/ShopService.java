package com.blockland.backend.shop;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.common.Ids;
import com.blockland.backend.media.MediaService;
import com.blockland.backend.player.PlayerAccount;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.shop.payment.PaymentProvider;
import com.blockland.backend.storage.Repository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Products, images and purchases. Payment goes through {@link PaymentProvider}; what a purchase unlocks
 * is an entitlement on the player account ({@link PlayerService#grant}). The shop knows nothing about
 * what an entitlement does in game.
 */
@Service
public class ShopService {
    /** Images are converted to PNG and scaled down so they can be sent to game clients. */
    private static final int MAX_IMAGE_SIZE = 256;
    private static final Pattern ENTITLEMENT = Pattern.compile("^[A-Z0-9_]{2,40}$");

    private final Repository<Product> products;
    private final Repository<Order> orders;
    private final PlayerService players;
    private final PaymentProvider payments;
    private final MediaService media;

    public ShopService(@Qualifier("shopProducts") Repository<Product> products, @Qualifier("shopOrders") Repository<Order> orders,
                       PlayerService players, PaymentProvider payments, MediaService media) {
        this.products = products;
        this.orders = orders;
        this.players = players;
        this.payments = payments;
        this.media = media;
    }

    public record PurchaseResult(boolean success, String message, Order order) {
    }

    // ---- catalogue --------------------------------------------------------------------------------

    public List<Product> list() {
        return products.findAll().stream().sorted(Comparator.comparingInt(Product::sortOrder).thenComparing(Product::id)).toList();
    }

    public List<Product> enabledProducts() {
        return list().stream().filter(Product::enabled).toList();
    }

    public boolean exists(String id) {
        return products.findById(id).isPresent();
    }

    public Product get(String id) {
        return products.findById(id).orElseThrow(() -> ApiException.notFound("商品", id));
    }

    public Product create(Product request) {
        if (request.id() != null && exists(request.id())) throw ApiException.conflict("商品 ID 已存在: " + request.id());
        return products.save(validate(request.id(), request, null));
    }

    public Product update(String id, Product request) {
        Product current = get(id);
        return products.save(validate(id, request, current.imageVersion()));
    }

    public void delete(String id) {
        get(id);
        products.deleteById(id);
        media.delete(MediaService.SHOP, id);
    }

    public Product setImage(String id, byte[] data) {
        Product product = get(id);
        String version = media.store(MediaService.SHOP, id, data, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE);
        return products.save(product.withImageVersion(version, Instant.now()));
    }

    // ---- purchases --------------------------------------------------------------------------------

    public PurchaseResult purchase(String playerUuid, String playerName, String productId) {
        Product product = products.findById(productId).filter(Product::enabled).orElse(null);
        if (product == null) return new PurchaseResult(false, "商品不存在或已下架", null);
        PlayerService.PlayerStatus status = players.status(playerUuid).orElse(null);
        if (status == null) return new PurchaseResult(false, "玩家不存在", null);
        boolean ownsForever = status.entitlements().stream()
                .anyMatch(e -> e.key().equals(product.entitlement()) && e.until() == null);
        if (ownsForever) return new PurchaseResult(false, "你已永久拥有「" + product.name() + "」", null);

        String orderId = UUID.randomUUID().toString();
        PaymentProvider.Result payment = payments.charge(new PaymentProvider.Charge(orderId, status.uuid(), status.name(),
                product.name(), product.priceCents(), product.currency()));
        Order order = new Order(orderId, status.uuid(), playerName != null ? playerName : status.name(), product.id(), product.name(),
                product.priceCents(), product.currency(), payment.success() ? Order.Status.PAID : Order.Status.FAILED,
                payments.id(), payment.reference(), payment.message(), Instant.now());
        orders.save(order);
        if (!payment.success()) return new PurchaseResult(false, "支付失败: " + payment.message(), order);

        players.grant(status.uuid(), product.entitlement(), product.durationDays(), "order:" + orderId);
        return new PurchaseResult(true, "购买成功：「" + product.name() + "」已生效", order);
    }

    public List<Order> orders() {
        return orders.findAll().stream().sorted(Comparator.comparing(Order::createdAt).reversed()).toList();
    }

    /** Entitlements currently held by a player, for the in-game shop ("已拥有"). */
    public List<PlayerAccount.Entitlement> ownedBy(String playerUuid) {
        return players.status(playerUuid).map(PlayerService.PlayerStatus::entitlements).orElse(List.of());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private Product validate(String id, Product request, String imageVersion) {
        List<String> errors = new ArrayList<>();
        Ids.check(id, "商品 ID", errors);
        if (request.name() == null || request.name().isBlank()) errors.add("商品名称不能为空");
        if (request.priceCents() < 0) errors.add("价格不能为负数");
        String entitlement = request.entitlement() == null ? "" : request.entitlement().trim().toUpperCase(Locale.ROOT);
        if (!ENTITLEMENT.matcher(entitlement).matches()) errors.add("权益标识只能包含大写字母、数字和下划线，例如 PRIORITY_QUEUE");
        if (request.durationDays() < 0 || request.durationDays() > 3650) errors.add("有效期应在 0-3650 天之间（0 = 永久）");
        if (!errors.isEmpty()) throw ApiException.invalid(errors);
        String category = request.category() == null || request.category().isBlank() ? "其他" : request.category().trim();
        String currency = request.currency() == null || request.currency().isBlank() ? "CNY" : request.currency().trim().toUpperCase(Locale.ROOT);
        return new Product(id, request.name().trim(), request.description() == null ? "" : request.description().trim(), category,
                request.priceCents(), currency, entitlement, request.durationDays(), request.enabled(), request.sortOrder(),
                imageVersion, Instant.now());
    }

}
