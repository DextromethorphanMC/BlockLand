package com.blockland.backend.shop;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** HTTP endpoints of the shop: admin management and Minecraft servers (images are served by the media module). */
public final class ShopControllers {
    private ShopControllers() {
    }

    /** 商店管理. */
    @RestController
    @RequestMapping("/api/admin/shop")
    public static class Admin {
        private final ShopService shop;

        public Admin(ShopService shop) {
            this.shop = shop;
        }

        @GetMapping("/products")
        public List<Product> products() {
            return shop.list();
        }

        @PostMapping("/products")
        @ResponseStatus(HttpStatus.CREATED)
        public Product create(@RequestBody Product product) {
            return shop.create(product);
        }

        @PutMapping("/products/{id}")
        public Product update(@PathVariable String id, @RequestBody Product product) {
            return shop.update(id, product);
        }

        @DeleteMapping("/products/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable String id) {
            shop.delete(id);
        }

        @PostMapping(value = "/products/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        public Product image(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
            return shop.setImage(id, file.getBytes());
        }

        @GetMapping("/orders")
        public List<Order> orders() {
            return shop.orders();
        }
    }

    /** Catalogue and purchases for Minecraft servers (the in-game shop). */
    @RestController
    @RequestMapping("/api/server/shop")
    public static class Server {
        private final ShopService shop;

        public Server(ShopService shop) {
            this.shop = shop;
        }

        public record PurchaseRequest(String uuid, String name, String productId) {
        }

        @GetMapping("/products")
        public List<Product> products() {
            return shop.enabledProducts();
        }

        @PostMapping("/purchase")
        public ShopService.PurchaseResult purchase(@RequestBody PurchaseRequest request) {
            return shop.purchase(request.uuid(), request.name(), request.productId());
        }
    }
}
