package com.blockland.client.hud.compass;

import com.blockland.client.hud.layout.HudLayout;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * The compass strip on the HUD (UI spec §3.6): a movable, resizable {@link CompassElement} — the horizon's bearings
 * sliding past a fixed centre, team-mates marked where they are.
 */
public final class Compass {
    private Compass() {
    }

    public static void init(BusGroup modBus) {
        HudLayout.register(new CompassElement());
    }
}
