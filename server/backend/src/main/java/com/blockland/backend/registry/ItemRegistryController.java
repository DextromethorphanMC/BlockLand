package com.blockland.backend.registry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/registry")
public class ItemRegistryController {
    private final ItemRegistryService items;

    public ItemRegistryController(ItemRegistryService items) {
        this.items = items;
    }

    /** Item ids reported by the Minecraft server, for autocomplete in kit and loot editors. */
    @GetMapping("/items")
    public List<String> items() {
        return items.items();
    }
}
