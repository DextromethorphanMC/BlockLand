package com.blockland.backend.gametype.infection;

import com.blockland.backend.map.MapService;
import com.blockland.backend.map.ShippedMaps;
import com.blockland.backend.seed.DemoSeed;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The harbour stored before it stood on a layer of stone (in the arena its lake's sand floor fell into the void and the
 * lake drained): it gets the corrected file, with its spawns one block higher to match. A fresh install already has the
 * corrected file and nothing happens.
 */
@Component
@Order(63)
public class InfectionHarborFixSeed implements DemoSeed {
    private final MapService maps;
    private final ShippedMaps shipped;

    public InfectionHarborFixSeed(MapService maps, ShippedMaps shipped) {
        this.maps = maps;
        this.shipped = shipped;
    }

    @Override
    public String id() {
        return "infection-harbor-void-fix-v1";
    }

    @Override
    public void apply() {
        String id = InfectionHarborSeed.MAP_ID;
        if (!maps.exists(id) || !InfectionDefinition.ID.equalsIgnoreCase(maps.get(id).gameType())) return;
        shipped.refresh(id);
    }
}
