package com.blockland.client.screen;

import com.blockland.client.ClientMedia;
import com.blockland.client.ClientShopState;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Tr;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.ShopPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.blockland.client.ui.Tr.tr;

/** The lobby's "商店" tab: product cards by category, purchase confirmation (payment is mocked by the backend). */
final class ShopPanel {
    private static final int CARD_W = 224;
    private static final int CARD_H = 100;
    private static final int GAP = 10;
    private static final int IMAGE = 64;

    private static final int C_PANEL = 0xB0101722;
    private static final int C_CARD = 0xCC1A2230;
    private static final int C_CARD_HOVER = 0xCC232D3F;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_MUTED = 0xFF8D99AE;
    private static final int C_DIM = 0xFF5C6678;
    private static final int C_ACCENT = 0xFFF5A623;
    private static final int C_GO = 0xFF3FA34D;
    private static final int C_GO_HOVER = 0xFF4CBB5B;
    private static final int C_ERROR = 0xFFE57373;

    private String category = "";
    @Nullable
    private ShopPackets.Product confirming;
    private boolean waiting;
    private String resultSeen = "";
    private double scroll;
    private int x, y, w, h;

    ShopPanel() {
    }

    void open() {
        ClientShopState.request();
        resultSeen = ClientShopState.lastResult();
    }

    // ---- layout ----------------------------------------------------------------------------

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private List<String> categories() {
        Set<String> set = new LinkedHashSet<>();
        for (ShopPackets.Product p : ClientShopState.products()) set.add(p.category());
        List<String> list = new ArrayList<>();
        list.add("");
        list.addAll(set);
        return list;
    }

    private List<ShopPackets.Product> visible() {
        return ClientShopState.products().stream().filter(p -> category.isEmpty() || p.category().equals(category)).toList();
    }

    private List<Rect> chipRects() {
        List<Rect> rects = new ArrayList<>();
        int cx = x + 60;
        for (String c : categories()) {
            int cw = UiFont.width(c.isEmpty() ? tr("blockland.shop.all") : c) + 14;
            rects.add(new Rect(cx, y + 2, cw, 14));
            cx += cw + 4;
        }
        return rects;
    }

    private int gridTop() {
        return y + 28;
    }

    private int gridBottom() {
        return y + h - 18;
    }

    private int columns() {
        return Math.max(1, (w + GAP) / (CARD_W + GAP));
    }

    private Rect cardRect(int index) {
        int cols = columns();
        int col = index % cols;
        int row = index / cols;
        return new Rect(x + col * (CARD_W + GAP), gridTop() + row * (CARD_H + GAP) - (int) scroll, CARD_W, CARD_H);
    }

    private Rect buyRect(Rect card) {
        return new Rect(card.x + card.w - 64, card.y + card.h - 24, 56, 16);
    }

    private Rect confirmBox() {
        int bw = 260, bh = 120;
        return new Rect(x + (w - bw) / 2, y + (h - bh) / 2, bw, bh);
    }

    private Rect confirmButton() {
        Rect box = confirmBox();
        return new Rect(box.x + box.w - 150, box.y + box.h - 26, 76, 18);
    }

    private Rect cancelButton() {
        Rect box = confirmBox();
        return new Rect(box.x + box.w - 68, box.y + box.h - 26, 58, 18);
    }

    // ---- rendering ---------------------------------------------------------------------------

    void render(GuiGraphics g, int mouseX, int mouseY, int px, int py, int pw, int ph) {
        x = px;
        y = py;
        w = pw;
        h = ph;
        if (waiting && !ClientShopState.lastResult().equals(resultSeen)) {
            waiting = false;
            confirming = null;
        }

        g.fill(x - 8, y - 8, x + w + 8, y + h + 4, C_PANEL);
        UiFont.draw(g, tr("blockland.shop.title"), x, y + 5, C_TEXT, false);
        List<String> cats = categories();
        List<Rect> chips = chipRects();
        for (int i = 0; i < cats.size(); i++) {
            Rect r = chips.get(i);
            boolean active = cats.get(i).equals(category);
            g.fill(r.x, r.y, r.x + r.w, r.y + r.h, active ? 0xFF3A2E12 : r.contains(mouseX, mouseY) ? 0xFF2A3446 : 0xFF1C2638);
            if (active) g.renderOutline(r.x, r.y, r.w, r.h, C_ACCENT);
            UiFont.drawCentered(g, cats.get(i).isEmpty() ? tr("blockland.shop.all") : cats.get(i), r.x + r.w / 2, r.y + 3, active ? C_ACCENT : C_MUTED, true);
        }

        if (!ClientShopState.loaded()) {
            UiFont.draw(g, tr("blockland.shop.loading"), x, gridTop() + 6, C_MUTED, false);
        } else if (!ClientShopState.error().isEmpty()) {
            // the server sends a translation key (or plain text, which I18n shows unchanged)
            UiFont.draw(g, I18n.get(ClientShopState.error()), x, gridTop() + 6, C_ERROR, false);
        } else if (visible().isEmpty()) {
            UiFont.draw(g, tr("blockland.shop.empty"), x, gridTop() + 6, C_MUTED, false);
        }

        boolean modal = confirming != null;
        g.enableScissor(x - 2, gridTop(), x + w + 2, gridBottom());
        List<ShopPackets.Product> products = visible();
        for (int i = 0; i < products.size(); i++) {
            renderCard(g, products.get(i), cardRect(i), modal ? -1 : mouseX, modal ? -1 : mouseY);
        }
        g.disableScissor();

        String result = ClientShopState.lastResult();
        if (!result.isEmpty() && !result.equals(resultSeen) || (!result.isEmpty() && waiting)) {
            UiFont.draw(g, Tr.text(result), x, y + h - 10, ClientShopState.lastResultSuccess() ? 0xFF7EE787 : C_ERROR, false);
        } else {
            UiFont.draw(g, tr("blockland.shop.payment_note"), x, y + h - 10, C_DIM, false);
        }

        if (modal) renderConfirm(g, mouseX, mouseY);
    }

    private void renderCard(GuiGraphics g, ShopPackets.Product p, Rect r, int mouseX, int mouseY) {
        if (r.y + r.h < gridTop() || r.y > gridBottom()) return;
        g.fill(r.x, r.y, r.x + r.w, r.y + r.h, r.contains(mouseX, mouseY) ? C_CARD_HOVER : C_CARD);
        g.fill(r.x, r.y, r.x + 3, r.y + r.h, C_ACCENT);

        int ix = r.x + 10, iy = r.y + 10;
        ClientMedia.Image image = ClientMedia.get(MediaPackets.SHOP, p.id(), p.imageVersion());
        if (image != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, image.texture(), ix, iy, 0, 0, IMAGE, IMAGE,
                    image.width(), image.height(), image.width(), image.height());
        } else {
            g.fill(ix, iy, ix + IMAGE, iy + IMAGE, 0xFF0E1420);
        }

        int tx = ix + IMAGE + 10;
        int textW = r.x + r.w - 8 - tx;
        UiFont.draw(g, UiFont.substrByWidth(p.name(), textW), tx, r.y + 10, C_TEXT, false);
        UiFont.draw(g, p.category(), tx, r.y + 22, C_ACCENT, false);
        List<FormattedCharSequence> lines = UiFont.split(Component.literal(p.description()), textW);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            UiFont.draw(g, lines.get(i), tx, r.y + 35 + i * 10, C_MUTED, false);
        }

        UiFont.draw(g, price(p), r.x + 10, r.y + r.h - 20, C_ACCENT, false);
        String term = p.durationDays() > 0 ? tr("blockland.shop.days", p.durationDays()) : tr("blockland.shop.permanent");
        UiFont.draw(g, term, r.x + 10 + UiFont.width(price(p)) + 6, r.y + r.h - 20, C_DIM, false);

        Rect buy = buyRect(r);
        String owned = ownership(p);
        if (!owned.isEmpty()) UiFont.draw(g, owned, buy.x - 6 - UiFont.width(owned), buy.y + 4, 0xFF7EE787, false);
        boolean permanent = p.ownedUntil() == 0;
        String label = tr(permanent ? "blockland.shop.owned" : p.ownedUntil() > 0 ? "blockland.shop.renew" : "blockland.shop.buy");
        int color = permanent ? 0xFF3A4252 : buy.contains(mouseX, mouseY) ? C_GO_HOVER : C_GO;
        g.fill(buy.x, buy.y, buy.x + buy.w, buy.y + buy.h, color);
        UiFont.drawCentered(g, label, buy.x + buy.w / 2, buy.y + 4, C_TEXT, true);
    }

    private void renderConfirm(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(x - 8, y - 8, x + w + 8, y + h + 4, 0xA0000000);
        Rect box = confirmBox();
        g.fill(box.x, box.y, box.x + box.w, box.y + box.h, 0xF0161E2C);
        g.fill(box.x, box.y, box.x + 3, box.y + box.h, C_ACCENT);
        UiFont.draw(g, tr("blockland.shop.confirm_title"), box.x + 12, box.y + 10, C_TEXT, false);
        UiFont.draw(g, confirming.name(), box.x + 12, box.y + 28, C_ACCENT, false);
        UiFont.draw(g, tr("blockland.shop.price", price(confirming))
                        + "  ·  " + (confirming.durationDays() > 0 ? tr("blockland.shop.days", confirming.durationDays()) : tr("blockland.shop.permanent")), box.x + 12, box.y + 44, C_MUTED, false);
        UiFont.draw(g, tr("blockland.shop.payment_note_short"), box.x + 12, box.y + 58, C_DIM, false);

        Rect ok = confirmButton();
        Rect cancel = cancelButton();
        g.fill(ok.x, ok.y, ok.x + ok.w, ok.y + ok.h, waiting ? 0xFF3A4252 : ok.contains(mouseX, mouseY) ? C_GO_HOVER : C_GO);
        UiFont.drawCentered(g, tr(waiting ? "blockland.shop.processing" : "blockland.shop.pay"), ok.x + ok.w / 2, ok.y + 5, C_TEXT, true);
        g.fill(cancel.x, cancel.y, cancel.x + cancel.w, cancel.y + cancel.h, cancel.contains(mouseX, mouseY) ? 0xFF3A4458 : 0xFF2A3446);
        UiFont.drawCentered(g, tr("blockland.shop.cancel"), cancel.x + cancel.w / 2, cancel.y + 5, C_TEXT, true);
    }

    // ---- input -------------------------------------------------------------------------------

    boolean mouseClicked(double mx, double my) {
        if (confirming != null) {
            if (waiting) return true;
            if (confirmButton().contains(mx, my)) {
                resultSeen = ClientShopState.lastResult();
                waiting = true;
                BLNetwork.sendToServer(new ShopPackets.C2SPurchase(confirming.id()));
                click();
            } else if (cancelButton().contains(mx, my) || !confirmBox().contains(mx, my)) {
                confirming = null;
                click();
            }
            return true;
        }
        List<String> cats = categories();
        List<Rect> chips = chipRects();
        for (int i = 0; i < cats.size(); i++) {
            if (chips.get(i).contains(mx, my)) {
                category = cats.get(i);
                scroll = 0;
                click();
                return true;
            }
        }
        if (my < gridTop() || my > gridBottom()) return false;
        List<ShopPackets.Product> products = visible();
        for (int i = 0; i < products.size(); i++) {
            ShopPackets.Product p = products.get(i);
            if (buyRect(cardRect(i)).contains(mx, my) && p.ownedUntil() != 0) {
                confirming = p;
                click();
                return true;
            }
        }
        return false;
    }

    boolean mouseScrolled(double dy) {
        int rows = (visible().size() + columns() - 1) / columns();
        int content = rows * (CARD_H + GAP) - GAP;
        scroll = Math.max(0, Math.min(scroll - dy * 24, Math.max(0, content - (gridBottom() - gridTop()))));
        return true;
    }

    boolean isModalOpen() {
        return confirming != null;
    }

    void closeModal() {
        if (!waiting) confirming = null;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String price(ShopPackets.Product p) {
        String symbol = "CNY".equals(p.currency()) ? "¥" : p.currency() + " ";
        return symbol + String.format("%.2f", p.priceCents() / 100.0);
    }

    private static String ownership(ShopPackets.Product p) {
        if (p.ownedUntil() < 0) return "";
        if (p.ownedUntil() == 0) return tr("blockland.shop.owned_forever");
        long remaining = p.ownedUntil() - System.currentTimeMillis();
        long days = Math.max(0, (remaining + 86_399_999L) / 86_400_000L);
        return days > 0 ? tr("blockland.shop.days_left", days) : tr("blockland.shop.expired");
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
