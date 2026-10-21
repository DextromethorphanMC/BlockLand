package com.blockland.backend.shop;

import com.blockland.backend.config.BlockLandProperties;
import com.blockland.backend.storage.JsonFileRepository;
import com.blockland.backend.storage.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shop data lives under {@code data/shop/}; it is fetched on demand, so writes do not bump the config version. */
@Configuration
public class ShopStorageConfig {

    @Bean
    public Repository<Product> shopProducts(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("shop").resolve("products.json"), Product.class, Product::id, null);
    }

    @Bean
    public Repository<Order> shopOrders(ObjectMapper mapper, BlockLandProperties properties) {
        return new JsonFileRepository<>(mapper, properties.dataPath().resolve("shop").resolve("orders.json"), Order.class, Order::id, null);
    }
}
