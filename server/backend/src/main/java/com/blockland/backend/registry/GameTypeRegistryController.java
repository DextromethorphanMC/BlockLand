package com.blockland.backend.registry;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/registry/game-types")
public class GameTypeRegistryController {
    private final GameTypeRegistryData data;

    public GameTypeRegistryController(GameTypeRegistryData data) {
        this.data = data;
    }

    /** What the Minecraft server published for the game type (e.g. 枪战感染 monsters); {@code {}} until then. */
    @GetMapping("/{type}")
    public ObjectNode get(@PathVariable String type) {
        return data.get(type);
    }
}
