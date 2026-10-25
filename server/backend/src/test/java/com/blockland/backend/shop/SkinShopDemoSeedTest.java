package com.blockland.backend.shop;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.media.MediaService;
import com.blockland.backend.player.PlayerAccount;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.shop.payment.MockPaymentProvider;
import com.blockland.backend.storage.JsonFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkinShopDemoSeedTest {
    private static final String PLAYER = "0a1b2c3d-0000-4000-8000-000000000001";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private record Setup(ShopService shop, PlayerService players) {
    }

    private Setup setup(Path dir) {
        PlayerService players = new PlayerService(new JsonFileRepository<>(mapper, dir.resolve("players.json"), PlayerAccount.class,
                PlayerAccount::uuid, null));
        ShopService shop = new ShopService(
                new JsonFileRepository<>(mapper, dir.resolve("products.json"), Product.class, Product::id, null),
                new JsonFileRepository<>(mapper, dir.resolve("orders.json"), Order.class, Order::id, null),
                players, new MockPaymentProvider(), new MediaService(new BlockLandProperties(dir.toString(), null, null, null, false, null)));
        return new Setup(shop, players);
    }

    @Test
    void specialSkinsBecomePermanentItemProducts(@TempDir Path dir) {
        Setup s = setup(dir);
        new SkinShopDemoSeed(s.shop()).apply();

        List<Product> skins = s.shop().list().stream().filter(p -> p.entitlement().startsWith("skin:")).toList();
        assertThat(skins).extracting(Product::entitlement)
                .containsExactly("skin:usp_dual/cerberus", "skin:ks3/dragon", "skin:tommy/chicago", "skin:awp/thunder");
        assertThat(skins).allSatisfy(p -> {
            assertThat(p.durationDays()).isZero();
            assertThat(p.category()).isEqualTo("皮肤");
            assertThat(p.enabled()).isTrue();
        });
    }

    @Test
    void existingProductsAreLeftAlone(@TempDir Path dir) {
        Setup s = setup(dir);
        s.shop().create(new Product("skin_ks3_dragon", "我的龙息", "", "活动", 100, "CNY", "skin:ks3/dragon", 7, false, 0, null, null));
        new SkinShopDemoSeed(s.shop()).apply();

        Product kept = s.shop().get("skin_ks3_dragon");
        assertThat(kept.name()).isEqualTo("我的龙息");
        assertThat(kept.priceCents()).isEqualTo(100);
        assertThat(s.shop().list()).hasSize(4);
    }

    @Test
    void buyingASkinGrantsItsItemKeyForever(@TempDir Path dir) {
        Setup s = setup(dir);
        new SkinShopDemoSeed(s.shop()).apply();
        s.players().login(PLAYER, "Steve");

        assertThat(s.shop().purchase(PLAYER, "Steve", "skin_usp_dual_cerberus").success()).isTrue();
        PlayerService.PlayerStatus status = s.players().status(PLAYER).orElseThrow();
        assertThat(status.entitlements()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("skin:usp_dual/cerberus");
            assertThat(e.until()).isNull();
        });
        // owned for good: a second purchase is refused
        assertThat(s.shop().purchase(PLAYER, "Steve", "skin_usp_dual_cerberus").success()).isFalse();
    }
}
