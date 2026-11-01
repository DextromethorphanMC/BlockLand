package com.blockland.client.hud.chat;

import com.blockland.client.font.UiFont;
import com.blockland.mixin.chat.ChatComponentAccess;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;

import javax.annotation.Nullable;
import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * The chat's panel (UI spec §3.3) at one place on screen, from vanilla's chat options: its size in chat units (scaled by
 * the chat scale), the lines it shows and how faded each is, drawing, and what lies under a point. Layout matches
 * vanilla's own: newest line at the bottom of the page, the page the focused height, the queued-message line under it,
 * the scrollbar on the right; only the font, the panel's look and its place differ.
 *
 * <pre>
 *   0  4                 4+k   k+8 k+10  k+12   (chat units across; k = the chat's width / scale)
 *   ┌──────────────────────────────┬──┬──┐ top
 *   │  older lines …               │▐ │  │
 *   │  newest line                 │▐ │  │ bottom = page lines × line height
 *   ├──────────────────────────────┴──┴──┤
 *   │  N messages waiting (chat delay)    │ + 9
 *   └─────────────────────────────────────┘
 * </pre>
 */
final class ChatPanel {
    /** Rows' backing and the panel: a cool near-black; the scrollbar's thumb, its track; a new message while scrolled up. */
    private static final int PANEL = 0x0B0F14, THUMB = 0xF2F4F7, TRACK = 0xFFFFFF, NEW_MESSAGE = 0xE8B04A, EDGE = 0xFFFFFF;
    private static final int QUEUE_ROW = 9;

    final ChatComponent chat;
    final Options options;
    final float scale;
    /** Text width (k), line height, lines on a focused page — all chat units. */
    final int textWidth, lineHeight, pageLines;
    /** The panel's top-left corner on screen (GUI pixels). */
    final int x, y;

    private ChatPanel(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        this.chat = mc.gui.getChat();
        this.options = mc.options;
        this.scale = (float) chat.getScale();
        this.textWidth = Mth.ceil(chat.getWidth() / Math.max(0.01f, scale));
        this.lineHeight = lineHeight(options);
        this.pageLines = ChatComponent.getHeight(options.chatHeightFocused().get()) / lineHeight;
        this.x = x;
        this.y = y;
    }

    static int lineHeight(Options options) {
        return (int) (9.0 * (options.chatLineSpacing().get() + 1.0));
    }

    /** The panel at the chat element's place on a screen of {@code screenW} × {@code screenH}. */
    static ChatPanel placed(int screenW, int screenH) {
        int[] at = ChatHud.place(screenW, screenH);
        return new ChatPanel(at[0], at[1]);
    }

    /** The panel with its top-left corner at ({@code x}, {@code y}). */
    static ChatPanel atCorner(int x, int y) {
        return new ChatPanel(x, y);
    }

    // ---- size -------------------------------------------------------------------------------------

    int unitsWide() {
        return textWidth + 12;
    }

    /** The bottom of the page (where the newest line ends), chat units from the top. */
    int bottom() {
        return pageLines * lineHeight;
    }

    int unitsHigh() {
        return bottom() + QUEUE_ROW;
    }

    int width() {
        return Mth.ceil(unitsWide() * scale);
    }

    int height() {
        return Mth.ceil(unitsHigh() * scale);
    }

    // ---- state ------------------------------------------------------------------------------------

    private ChatComponentAccess access() {
        return (ChatComponentAccess) chat;
    }

    boolean hidden() {
        return options.chatVisibility().get() == ChatVisiblity.HIDDEN;
    }

    /** Vanilla's fade of a line by its age in ticks: full for 9 s, gone at 10 s. */
    static double fade(int age) {
        double d = Mth.clamp((1.0 - age / 200.0) * 10.0, 0.0, 1.0);
        return d * d;
    }

    /** Whether the line at {@code index} continues a message (not its first line): indented. */
    private static boolean continues(List<GuiMessage.Line> lines, int index) {
        return index + 1 < lines.size() && !lines.get(index + 1).endOfEntry();
    }

    private static float indent(List<GuiMessage.Line> lines, int index) {
        return continues(lines, index) ? ChatLines.indent() : 0;
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /** Draws the chat: {@code focused} (the chat screen: every line of the page, the scrollbar, a message's tag icon). */
    void render(GuiGraphics g, int ticks, int mouseX, int mouseY, boolean focused) {
        if (hidden()) return;
        List<GuiMessage.Line> lines = access().blockland$lines();
        int scroll = access().blockland$scroll();
        float textAlpha = (float) (options.chatOpacity().get() * 0.9 + 0.1);
        float backAlpha = options.textBackgroundOpacity().get().floatValue();
        double spacing = options.chatLineSpacing().get();
        int textOffset = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        int shown = Math.min(lines.size() - scroll, chat.getLinesPerPage());
        int hoveredEnd = focused ? endIndexAt(mouseX, mouseY) : -1;
        int bottom = bottom(), right = unitsWide();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        if (focused && shown > 0) {
            // the open chat: one panel behind the page's lines, a hairline over it
            int top = bottom - Math.max(0, shown) * lineHeight;
            g.fill(0, top, right, bottom, ARGB.color(backAlpha, ARGB.opaque(PANEL)));
            g.fill(0, top, right, top + 1, ARGB.color(backAlpha * 0.35f, ARGB.opaque(EDGE)));
        }
        int drawn = 0;
        for (int k = shown - 1; k >= 0; k--) {
            int index = k + scroll;
            GuiMessage.Line line = lines.get(index);
            if (line == null) continue;
            float f = focused ? 1 : (float) fade(ticks - line.addedTime());
            if (f <= 1e-5f) continue;
            drawn++;
            int rowBottom = bottom - k * lineHeight, rowTop = rowBottom - lineHeight;
            if (!focused) g.fill(0, rowTop, right, rowBottom, ARGB.color(f * backAlpha, ARGB.opaque(PANEL)));
            GuiMessageTag tag = line.tag();
            if (tag != null) {
                g.fill(0, rowTop, 2, rowBottom, ARGB.color(f * textAlpha, ARGB.opaque(tag.indicatorColor())));
                if (index == hoveredEnd && tag.icon() != null) {
                    int iconX = Mth.ceil(4 + indent(lines, index) + UiFont.width(line.content()) + 4);
                    tag.icon().draw(g, iconX, rowBottom + textOffset + 9 - tag.icon().height - 1);
                }
            }
            UiFont.draw(g, line.content(), 4 + indent(lines, index), rowBottom + textOffset, 1, ARGB.color(f * textAlpha, 0xFFFFFF), true);
        }
        long queued = Minecraft.getInstance().getChatListener().queueSize();
        if (queued > 0) {
            g.fill(2, bottom, textWidth + 8, bottom + QUEUE_ROW, ARGB.color(backAlpha, ARGB.opaque(PANEL)));
            UiFont.draw(g, Component.translatable("chat.queue", queued), 4, bottom + 1, ARGB.color(textAlpha * 0.5f, 0xFFFFFF), true);
        }
        if (focused && lines.size() > drawn && drawn > 0) scrollbar(g, lines.size(), drawn, scroll, bottom, textWidth + 8);
        g.pose().popMatrix();
    }

    /** The history's scrollbar beside the page: where the shown lines are among all of them. */
    private void scrollbar(GuiGraphics g, int total, int shown, int scroll, int bottom, int x) {
        int page = shown * lineHeight;
        int thumb = Math.max(4, page * shown / total);
        int below = scroll * page / total;
        boolean fresh = access().blockland$newSinceScroll();
        g.fill(x, bottom - page, x + 2, bottom, ARGB.color(0.12f, ARGB.opaque(TRACK)));
        g.fill(x, bottom - below - thumb, x + 2, bottom - below, ARGB.color(below > 0 ? 0.8f : 0.45f, ARGB.opaque(fresh ? NEW_MESSAGE : THUMB)));
    }

    /** The HUD editor's sample: a few lines of a chat, as the open chat would show them. */
    void renderSample(GuiGraphics g) {
        float backAlpha = Math.max(0.35f, options.textBackgroundOpacity().get().floatValue());
        double spacing = options.chatLineSpacing().get();
        int textOffset = (int) Math.round(-8.0 * (spacing + 1.0) + 4.0 * spacing);
        int bottom = bottom(), right = unitsWide();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.fill(0, 0, right, bottom, ARGB.color(backAlpha * 0.5f, ARGB.opaque(PANEL)));
        String[] sample = {tr("blockland.hud.chat.sample.1"), tr("blockland.hud.chat.sample.2"), tr("blockland.hud.chat.sample.3")};
        for (int i = 0; i < sample.length && i < pageLines; i++) {
            int rowBottom = bottom - (sample.length - 1 - i) * lineHeight;
            g.fill(0, rowBottom - lineHeight, right, rowBottom, ARGB.color(backAlpha, ARGB.opaque(PANEL)));
            if (i == 0) g.fill(0, rowBottom - lineHeight, 2, rowBottom, ARGB.opaque(0xD0D0D0));
            UiFont.draw(g, UiFont.fit(sample[i], textWidth), 4, rowBottom + textOffset, 1, 0xFFFFFFFF, true);
        }
        g.pose().popMatrix();
    }

    // ---- what is under the mouse (the chat screen) ------------------------------------------------

    /** A screen point in chat units across, from where the text starts. */
    private double chatX(double mouseX) {
        return (mouseX - x) / scale - 4.0;
    }

    /** A screen point in lines up from the page's bottom. */
    private double chatY(double mouseY) {
        return (y + bottom() * scale - mouseY) / (scale * lineHeight);
    }

    /** The index of the line at a screen point (vanilla's rules, BlockLand's layout), -1 for none. */
    private int lineIndexAt(double mouseX, double mouseY) {
        if (!chat.isChatFocused() || hidden()) return -1;
        double cx = chatX(mouseX), cy = chatY(mouseY);
        if (cx < -4.0 || cx > Mth.floor(chat.getWidth() / scale)) return -1;
        List<GuiMessage.Line> lines = access().blockland$lines();
        int count = Math.min(chat.getLinesPerPage(), lines.size());
        if (cy < 0.0 || cy >= count) return -1;
        int index = Mth.floor(cy + access().blockland$scroll());
        return index >= 0 && index < lines.size() ? index : -1;
    }

    /** The index of the last line of the message at a screen point, -1 for none. */
    private int endIndexAt(double mouseX, double mouseY) {
        int index = lineIndexAt(mouseX, mouseY);
        if (index < 0) return -1;
        List<GuiMessage.Line> lines = access().blockland$lines();
        while (index >= 0 && !lines.get(index).endOfEntry()) index--;
        return index;
    }

    @Nullable
    Style styleAt(double mouseX, double mouseY) {
        int index = lineIndexAt(mouseX, mouseY);
        if (index < 0) return null;
        List<GuiMessage.Line> lines = access().blockland$lines();
        FormattedCharSequence content = lines.get(index).content();
        return ChatLines.styleAt(content, chatX(mouseX) - indent(lines, index));
    }

    @Nullable
    GuiMessageTag tagAt(double mouseX, double mouseY) {
        int index = endIndexAt(mouseX, mouseY);
        if (index < 0) return null;
        List<GuiMessage.Line> lines = access().blockland$lines();
        GuiMessage.Line line = lines.get(index);
        GuiMessageTag tag = line.tag();
        if (tag == null) return null;
        double cx = chatX(mouseX);
        // the indicator strip on the left, or the tag's icon after the text
        if (cx < 0.0) return tag;
        if (tag.icon() == null) return null;
        double left = indent(lines, index) + UiFont.width(line.content()) + 4;
        return cx >= left && cx <= left + tag.icon().width ? tag : null;
    }

    /** The queued-message line under the page was clicked: the next waiting message is shown. */
    boolean queueClicked(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (!chat.isChatFocused() || mc.options.hideGui || hidden() || mc.getChatListener().queueSize() == 0) return false;
        double cx = (mouseX - x) / scale, cy = (mouseY - y) / scale;
        if (cx < 0 || cx > textWidth + 8 || cy < bottom() || cy >= bottom() + QUEUE_ROW) return false;
        mc.getChatListener().acceptNextDelayedMessage();
        return true;
    }
}
