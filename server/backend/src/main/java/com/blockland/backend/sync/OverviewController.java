package com.blockland.backend.sync;

import com.blockland.backend.gamemode.GameMode;
import com.blockland.backend.map.GameMap;
import com.blockland.backend.player.PlayerService;
import com.blockland.backend.registry.ItemRegistryService;
import com.blockland.backend.storage.ContentVersion;
import com.blockland.backend.storage.Repository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class OverviewController {
    private final ContentVersion version;
    private final Repository<GameMode> modes;
    private final Repository<GameMap> maps;
    private final ItemRegistryService items;
    private final PlayerService players;

    public OverviewController(ContentVersion version, Repository<GameMode> modes, Repository<GameMap> maps, ItemRegistryService items,
                              PlayerService players) {
        this.version = version;
        this.modes = modes;
        this.maps = maps;
        this.items = items;
        this.players = players;
    }

    public record Overview(long contentVersion, int modes, int enabledModes, int maps, int knownItems, int players, long onlinePlayers) {
    }

    /** Also used by the login page to verify the admin token. */
    @GetMapping("/overview")
    public Overview overview() {
        return new Overview(version.current(), modes.findAll().size(),
                (int) modes.findAll().stream().filter(GameMode::enabled).count(),
                maps.findAll().size(), items.items().size(), players.count(), players.onlineCount());
    }
}
