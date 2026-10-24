package com.blockland.backend.registry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The inventory registry: published by the game server at start-up, read by the admin UI. */
public final class InventoryRegistryControllers {
    private InventoryRegistryControllers() {
    }

    @RestController
    @RequestMapping("/api/server/registry/inventory")
    public static class Server {
        private final InventoryRegistryService registry;

        public Server(InventoryRegistryService registry) {
            this.registry = registry;
        }

        @PostMapping
        public Map<String, Integer> publish(@RequestBody List<InventoryRegistryService.Item> items) {
            return Map.of("count", registry.replace(items));
        }
    }

    @RestController
    @RequestMapping("/api/admin/registry/inventory")
    public static class Admin {
        private final InventoryRegistryService registry;

        public Admin(InventoryRegistryService registry) {
            this.registry = registry;
        }

        /** Everything players can own, for the grant picker and the shop's entitlement field. */
        @GetMapping
        public List<InventoryRegistryService.Item> items() {
            return registry.items();
        }
    }
}
