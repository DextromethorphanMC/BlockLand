package com.blockland.backend.shop;

import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The special skins (their own model or shot effects) as permanent products: they are inventory items players must own
 * ({@code skin:<weapon>/<skin>}, the mod's {@code SkinKeys}). Products that already exist are left alone; without an
 * image the game shows the skin's own item icon.
 */
@Component
@Order(31)
public class SkinShopDemoSeed implements DemoSeed {
    private record Skin(String productId, String name, String description, int priceCents, String key) {
    }

    private static final List<Skin> SKINS = List.of(
            new Skin("skin_usp_dual_cerberus", "双持 USP | 地狱犬",
                    "特殊皮肤：独立模型、深红三叉枪口火焰、红色曳光与专属枪声，附带专属检视与击杀特效。永久拥有，在「工匠」中装备。",
                    3800, "skin:usp_dual/cerberus"),
            new Skin("skin_ks3_dragon", "KS-3 | 龙息",
                    "特殊皮肤：熔岩材质，巨大的橙色枪口火焰与燃烧曳光。永久拥有，在「工匠」中装备。",
                    2800, "skin:ks3/dragon"),
            new Skin("skin_tommy_chicago", "汤姆逊 | 芝加哥",
                    "特殊皮肤：鎏金金属，金色枪口火焰与曳光。永久拥有，在「工匠」中装备。",
                    2800, "skin:tommy/chicago"),
            new Skin("skin_awp_thunder", "AWP | 雷霆",
                    "特殊皮肤：淡蓝色枪口火焰与曳光。永久拥有，在「工匠」中装备。",
                    2800, "skin:awp/thunder"));

    private final ShopService shop;

    public SkinShopDemoSeed(ShopService shop) {
        this.shop = shop;
    }

    @Override
    public String id() {
        return "shop-special-skins";
    }

    @Override
    public void apply() {
        int order = 10;
        for (Skin skin : SKINS) {
            if (!shop.exists(skin.productId())) {
                shop.create(new Product(skin.productId(), skin.name(), skin.description(), "皮肤", skin.priceCents(), "CNY",
                        skin.key(), 0, true, order, null, null));
            }
            order++;
        }
    }
}
