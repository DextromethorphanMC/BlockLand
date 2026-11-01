package com.blockland.client.hud.minimap;

import com.blockland.client.hud.layout.HudLayout;
import com.blockland.client.settings.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The minimap (UI spec §3.4): a movable HUD element ({@link MinimapElement}) over a picture of the columns round the
 * player that a bounded amount of sampling keeps up to date each tick ({@link MinimapCache}). Other modules that show a
 * map of their own hide it meanwhile through {@link #hideWhile}; the minimap knows nothing of them.
 */
public final class Minimap {
    private static final MinimapCache cache = new MinimapCache();
    private static final MinimapElement element = new MinimapElement(cache);
    private static final List<BooleanSupplier> hiders = new ArrayList<>();

    private Minimap() {
    }

    public static void init(BusGroup modBus) {
        HudLayout.register(element);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> cache.reset());
    }

    /** Hides the minimap while {@code condition} holds (a mode's HUD shows its own map). */
    public static void hideWhile(BooleanSupplier condition) {
        hiders.add(condition);
    }

    static boolean hidden() {
        for (BooleanSupplier h : hiders) if (h.getAsBoolean()) return true;
        return false;
    }

    static MinimapSettings settings() {
        return ClientSettings.get().minimap;
    }

    /** Keeps the picture up to date while it can be seen (on the HUD or in the HUD editor). */
    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        boolean shown = HudLayout.editing() || HudLayout.enabled(element) && element.visible();
        if (shown) cache.tick(mc, settings().range);
    }
}
