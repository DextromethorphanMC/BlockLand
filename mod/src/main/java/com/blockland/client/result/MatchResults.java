package com.blockland.client.result;

import com.blockland.BlockLand;
import com.blockland.client.ClientKitLayouts;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.C2SPostMatch;
import com.blockland.network.packet.S2CMatchResult;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The end-of-match results panel. The server sends the result when the match ends; the panel shows (as
 * {@link MatchResultScreen}, with its two buttons) for exactly the time until the server sends the player back to the
 * lobby, and is gone the moment the player leaves the match world. Nothing of it reaches the lobby.
 */
public final class MatchResults {
    @Nullable
    private static S2CMatchResult result;
    private static long shownAt;
    private static long endsAt;
    @Nullable
    private static ClientLevel level;

    private MatchResults() {
    }

    public static void init(BusGroup modBus) {
        // the card on the HUD, for a player who closed the panel with Esc
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw().add(BlockLand.id("match_result"), MatchResults::renderHud));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            if (result != null && (Minecraft.getInstance().level != level || System.nanoTime() >= endsAt)) result = null;
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> result = null);
    }

    public static void show(S2CMatchResult received) {
        Minecraft mc = Minecraft.getInstance();
        result = received;
        level = mc.level;
        shownAt = System.nanoTime();
        endsAt = shownAt + received.showSeconds() * 1_000_000_000L;
        mc.setScreen(new MatchResultScreen());
    }

    /** Whether a results panel (or its HUD card) is up right now. */
    public static boolean visible() {
        return active();
    }

    static boolean active() {
        return result != null && Minecraft.getInstance().level == level && System.nanoTime() < endsAt;
    }

    static MatchResultCard.Layout renderCard(GuiGraphics g, boolean interactive, double mouseX, double mouseY) {
        S2CMatchResult r = result;
        if (r == null) return null;
        long now = System.nanoTime();
        return MatchResultCard.render(g, r, (now - shownAt) / 1e9f, Math.max(0, (endsAt - now) / 1e9f),
                interactive, mouseX, mouseY);
    }

    private static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && !mc.options.hideGui && active()) renderCard(g, false, -1, -1);
    }

    /** Queue the same mode again with the player's saved kit arrangement (the server checks everything). */
    static void playAgain() {
        S2CMatchResult r = result;
        if (r == null) return;
        var layout = ClientKitLayouts.any(r.modeId());
        BLNetwork.sendToServer(new C2SPostMatch(true, layout.map(ClientKitLayouts.Layout::signature).orElse(""),
                layout.map(ClientKitLayouts.Layout::slots).orElse(List.of())));
        result = null;
    }

    static void backToLobby() {
        BLNetwork.sendToServer(new C2SPostMatch(false, "", List.of()));
        result = null;
    }
}
