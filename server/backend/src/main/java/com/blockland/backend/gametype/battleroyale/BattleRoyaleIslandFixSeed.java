package com.blockland.backend.gametype.battleroyale;

import com.blockland.backend.map.MapService;
import com.blockland.backend.map.ShippedMaps;
import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The demo island stored before its seabed stood on sandstone and a barrier held its sea in (in the arena the sand fell
 * into the void and the sea hung and poured over the edge): it gets the corrected file. The loot spots are where they
 * were; nothing else changes. A fresh install already has the corrected file and nothing happens.
 */
@Component
@Order(69)
public class BattleRoyaleIslandFixSeed implements DemoSeed {
    private final MapService maps;
    private final ShippedMaps shipped;

    public BattleRoyaleIslandFixSeed(MapService maps, ShippedMaps shipped) {
        this.maps = maps;
        this.shipped = shipped;
    }

    @Override
    public String id() {
        return "br-island-void-fix-v1";
    }

    @Override
    public void apply() {
        String id = BattleRoyaleDemoSeed.DEMO_MAP;
        if (!maps.exists(id) || !BattleRoyaleDefinition.ID.equalsIgnoreCase(maps.get(id).gameType())) return;
        shipped.refresh(id);
    }
}
