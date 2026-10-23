package com.blockland.client.combat;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.CombatPacketListeners;
import com.blockland.network.packet.CombatPackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Kill feedback and the Tab board on the HUD: the kill medals below the crosshair ({@link KillMedals}), the kill feed
 * in the corner ({@link KillFeedHud}) and our board in place of the vanilla player list ({@link MatchBoardHud}), which
 * is switched off. Fed by the server's kill tracker; knows nothing about the weapons.
 */
public final class KillFeedback {
    private static boolean boardKey;

    private KillFeedback() {
    }

    public static void init(BusGroup modBus) {
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> {
            ForgeLayeredDraw draw = event.getLayeredDraw();
            // the vanilla player list never shows: our board replaces it
            draw.addConditionTo(ForgeLayeredDraw.POST_SLEEP_STACK, ForgeLayeredDraw.TAB_LIST, () -> false);
            draw.addAbove(ForgeLayeredDraw.POST_SLEEP_STACK, BlockLand.id("match_board"), ForgeLayeredDraw.TAB_LIST, KillFeedback::renderBoard);
            draw.addBelow(ForgeLayeredDraw.POST_SLEEP_STACK, BlockLand.id("kill_feedback"), ForgeLayeredDraw.TITLE_OVERLAY, KillFeedback::renderHud);
        });
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> clear());
        BLNetwork.setCombatClientListener(new CombatPacketListeners.Client() {
            @Override
            public void onKillNotice(CombatPackets.S2CKillNotice notice) {
                KillMedals.show(notice);
            }

            @Override
            public void onKillFeed(CombatPackets.S2CKillFeed feed) {
                KillFeedHud.add(feed);
            }

            @Override
            public void onBoard(CombatPackets.S2CBoard board) {
                MatchBoardHud.update(board);
            }
        });
    }

    /** Holding the player-list key asks the server for the board (and stops it on release). */
    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean held = mc.level != null && mc.player != null && mc.screen == null && mc.options.keyPlayerList.isDown();
        if (held == boardKey) return;
        boardKey = held;
        MatchBoardHud.setOpen(held);
        if (mc.getConnection() != null) BLNetwork.sendToServer(new CombatPackets.C2SBoard(held));
    }

    private static void clear() {
        boardKey = false;
        KillMedals.clear();
        KillFeedHud.clear();
        MatchBoardHud.clear();
    }

    private static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int w = g.guiWidth(), h = g.guiHeight();
        KillMedals.render(g, w, h);
        KillFeedHud.render(g, w, h);
    }

    private static void renderBoard(GuiGraphics g, DeltaTracker delta) {
        if (Minecraft.getInstance().level == null) return;
        MatchBoardHud.render(g, g.guiWidth(), g.guiHeight());
    }
}
