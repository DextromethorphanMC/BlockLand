package com.blockland.client.hud.chat;

import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.hud.layout.HudLayout;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

import javax.annotation.Nullable;
import java.util.List;

/**
 * BlockLand's chat in place of vanilla's (UI spec §3.3), a movable HUD element ({@code chat}, late, where vanilla's
 * sits by default). Vanilla keeps everything the chat does — messages, history, the chat delay queue, deletions,
 * scrolling, the chat screen — and its options; BlockLand wraps the messages with its own font, draws them on its
 * panel at the element's place, and answers the chat screen's "what is under the mouse" from that layout
 * ({@code mixin/chat}). While the HUD's vanilla switch is off nothing here takes part and vanilla's chat is untouched.
 */
public final class ChatHud {
    private static final Element ELEMENT = new Element();
    /** Whether BlockLand's chat was taking part at the last tick (a change wraps the messages again). */
    private static boolean wasActive;
    private static boolean rewrap;

    private ChatHud() {
    }

    public static void init(BusGroup modBus) {
        HudLayout.register(ELEMENT);
        // vanilla's chat layer stands down while BlockLand's draws the chat
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addConditionTo(ForgeLayeredDraw.POST_SLEEP_STACK, ForgeLayeredDraw.CHAT_OVERLAY, () -> !active()));
        // a new font (a resource reload) or switching between the chats lays the messages out again
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> rewrap = true));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
    }

    /** Whether BlockLand draws the chat (the HUD's vanilla switch). */
    public static boolean active() {
        return HudLayout.replacesVanilla();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean active = active();
        if (active != wasActive || rewrap) {
            wasActive = active;
            rewrap = false;
            if (mc.gui != null) mc.gui.getChat().rescaleChat();
        }
    }

    /** The chat's place on a screen of {@code screenW} × {@code screenH} (its box's top-left corner). */
    static int[] place(int screenW, int screenH) {
        return HudLayout.place(ELEMENT, screenW, screenH);
    }

    private static ChatPanel panel() {
        var window = Minecraft.getInstance().getWindow();
        return ChatPanel.placed(window.getGuiScaledWidth(), window.getGuiScaledHeight());
    }

    // ---- what the mixins forward ----------------------------------------------------------------------

    /** A message's lines in BlockLand's font, {@code width} chat units wide. */
    public static List<FormattedCharSequence> wrap(FormattedText text, int width) {
        return ChatLines.wrap(text, width);
    }

    /** The chat screen draws the open chat: at the element's place. */
    public static void renderFocused(GuiGraphics g, int ticks, int mouseX, int mouseY) {
        ChatPanel.placed(g.guiWidth(), g.guiHeight()).render(g, ticks, mouseX, mouseY, true);
    }

    @Nullable
    public static Style styleAt(double mouseX, double mouseY) {
        return panel().styleAt(mouseX, mouseY);
    }

    @Nullable
    public static GuiMessageTag tagAt(double mouseX, double mouseY) {
        return panel().tagAt(mouseX, mouseY);
    }

    public static boolean queueClicked(double mouseX, double mouseY) {
        return panel().queueClicked(mouseX, mouseY);
    }

    // ---- the element ------------------------------------------------------------------------------

    /** The chat on the HUD: the closed chat's fading lines (the open chat is drawn by the chat screen). */
    private static final class Element implements HudElement {
        @Override
        public String id() {
            return "chat";
        }

        @Override
        public String nameKey() {
            return "blockland.hud.element.chat";
        }

        /** The whole open chat's box: the page at the focused height and the queued-message line. */
        @Override
        public int width() {
            return ChatPanel.atCorner(0, 0).width();
        }

        @Override
        public int height() {
            return ChatPanel.atCorner(0, 0).height();
        }

        @Override
        public HudAnchor defaultAnchor() {
            return HudAnchor.BOTTOM_LEFT;
        }

        @Override
        public int defaultX() {
            return 0;
        }

        /** Vanilla's place: the page ends 40 pixels over the bottom, the queued-message line under it. */
        @Override
        public int defaultY() {
            return -40 + Mth.ceil(9 * Minecraft.getInstance().gui.getChat().getScale());
        }

        @Override
        public boolean visible() {
            Minecraft mc = Minecraft.getInstance();
            return active() && mc.gui != null && !mc.gui.getChat().isChatFocused();
        }

        @Override
        public boolean late() {
            return true;
        }

        @Override
        public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
            Minecraft mc = Minecraft.getInstance();
            ChatPanel panel = ChatPanel.atCorner(x, y);
            if (editing) {
                panel.renderSample(g);
                return;
            }
            // over the rest of the HUD, as vanilla's chat
            g.nextStratum();
            panel.render(g, mc.gui.getGuiTicks(), 0, 0, false);
        }
    }
}
