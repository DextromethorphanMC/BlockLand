package com.blockland.client.screen;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientActivities;
import com.blockland.client.ClientMedia;
import com.blockland.client.font.UiFont;
import com.blockland.client.screen.lobby.ActivityStatus;
import com.blockland.client.screen.lobby.TopBar;
import com.blockland.client.screen.lobby.activity.ActivityBodies;
import com.blockland.client.screen.lobby.activity.ActivityBody;
import com.blockland.client.ui.Draw;
import com.blockland.client.ui.ToastArea;
import com.blockland.network.packet.ActivityPackets;
import com.blockland.network.packet.MediaPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * One activity's own page, opened from the lobby's activities tab: a header with its cover, title, phase and dates, and
 * below it the body its {@code kind} asks for ({@link ActivityBodies}). Works without a world (it is opened from the
 * lobby); Escape and the back button return to the screen it was opened from. This class only lays out and wires.
 */
public final class ActivityScreen extends Screen implements ToastArea {
    private static final int BAR = TopBar.HEIGHT;
    private static final int PAD = 16;
    private static final int MAX_W = 760;
    private static final int HERO_H = 112;

    private final Screen parent;
    private final String activityId;
    @Nullable
    private ActivityPackets.Activity activity;
    @Nullable
    private ActivityBody body;
    private long seenVersion = -1;
    private final AnimValue intro = new AnimValue(0);
    private final Spring backHover = new Spring(0).config(320, 26);
    private int backX, backY, backW, backH;

    public ActivityScreen(Screen parent, String activityId) {
        super(Component.translatable("blockland.activity.title"));
        this.parent = parent;
        this.activityId = activityId;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        Anim.fromTo(intro, 0, 1, 0.45).ease(Ease.CUBIC_OUT);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Picks up a changed or withdrawn activity; the body is rebuilt only when the activity itself changed. */
    private void refresh() {
        if (seenVersion == ClientActivities.version()) return;
        seenVersion = ClientActivities.version();
        ActivityPackets.Activity now = ClientActivities.find(activityId).orElse(null);
        if (now == null || !now.equals(activity)) body = now == null ? null : ActivityBodies.create(now);
        activity = now;
    }

    @Override
    public void tick() {
        if (body != null) body.tick();
    }

    // ---- rendering -----------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        g.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
        int accent = activity == null ? INFO : ActivityStatus.accent(activity);
        double t = Ticker.global().time();
        Draw.glow(g, (float) (width * (0.7 + 0.05 * Math.sin(t * 0.2))), height * 0.3f, width * 0.45f, height * 0.5f,
                ColorMath.withAlpha(accent, 0x22));
        Draw.fadeY(g, 0, height - 70, width, 70, 0x70000000, false);
    }

    /** Toasts (notices, vanilla's warnings) start under the top bar, never over its buttons. */
    @Override
    public int toastTop() {
        return BAR + 4;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.render(g, mouseX, mouseY, partialTick);
        float alpha = intro.get();
        float shift = (1 - alpha) * 10;

        int contentW = Math.min(MAX_W, width - PAD * 2 - 8);
        int x = (width - contentW) / 2;
        header(g, mouseX, mouseY, x, contentW);

        if (activity == null) {
            String gone = tr("blockland.activity.gone");
            Draw.text(g, gone, (width - UiFont.width(gone)) / 2f, height / 2f - 4, 1f, ColorMath.fade(MUTED, alpha), false);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(0, shift);
        int heroY = BAR + PAD;
        hero(g, activity, x, heroY, contentW, alpha);
        int bodyY = heroY + HERO_H + 10;
        int bodyH = height - PAD - bodyY;
        Draw.rect(g, x, bodyY, contentW, bodyH, ColorMath.fade(PANEL, alpha));
        Draw.outline(g, x, bodyY, contentW, bodyH, ColorMath.fade(LINE, alpha));
        g.pose().popMatrix();
        // the body draws unshifted so its own hit areas match what it shows once the intro is over
        if (body != null) body.render(g, mouseX, mouseY, x + 14, bodyY + 12, contentW - 28, bodyH - 24, alpha);
    }

    /** The bar on top: back button and where the player is ("活动 / title"). */
    private void header(GuiGraphics g, int mouseX, int mouseY, int x, int w) {
        g.fill(0, 0, width, BAR, 0xE605070B);
        Draw.fadeY(g, 0, BAR, width, 10, 0x60000000, true);
        g.fill(0, BAR - 1, width, BAR, LINE);

        String back = "‹ " + tr("blockland.activity.back");
        backW = UiFont.width(back) + 14;
        backH = 16;
        backX = PAD;
        backY = 8;
        backHover.setTarget(inside(mouseX, mouseY, backX, backY, backW, backH) ? 1 : 0);
        float hv = backHover.get();
        Draw.rect(g, backX, backY, backW, backH, ColorMath.mix(CARD, CARD_HOVER, 0.3f + 0.7f * hv));
        Draw.outline(g, backX, backY, backW, backH, ColorMath.fade(NEUTRAL_EDGE, 0.25f + 0.5f * hv));
        Draw.text(g, back, backX + 7, backY + 4, 1f, ColorMath.mix(MUTED, TEXT, hv), false);

        int tx = backX + backW + 14;
        String section = tr("blockland.activity.title");
        Draw.text(g, section, tx, 12, 1f, MUTED, false);
        if (activity != null) {
            tx += UiFont.width(section);
            String crumb = "  /  " + activity.title();
            Draw.text(g, Draw.fit(crumb, width - PAD - tx), tx, 12, 1f, DIM, false);
        }
    }

    /** Cover, badges, title, summary and dates. */
    private void hero(GuiGraphics g, ActivityPackets.Activity a, int x, int y, int w, float alpha) {
        int accent = ActivityStatus.accent(a);
        Draw.rect(g, x, y, w, HERO_H, ColorMath.fade(CARD, alpha));
        ClientMedia.Image cover = a.coverVersion().isEmpty() ? null : ClientMedia.get(MediaPackets.ACTIVITY, a.id(), a.coverVersion());
        if (cover != null) {
            Draw.cover(g, cover, x + 1, y + 1, w - 2, HERO_H - 2, 1, ColorMath.fade(0xFFFFFFFF, alpha * 0.9f));
            Draw.fadeX(g, x + 1, y + 1, w * 3 / 4, HERO_H - 2, ColorMath.fade(0xE80A0F18, alpha), true);
        } else {
            Draw.glow(g, x + w * 0.85f, y + HERO_H * 0.3f, w * 0.4f, HERO_H * 0.8f, ColorMath.fade(ColorMath.withAlpha(accent, 0x38), alpha));
        }
        Draw.rect(g, x, y, 3, HERO_H, ColorMath.fade(accent, alpha));
        Draw.outline(g, x, y, w, HERO_H, ColorMath.fade(LINE, alpha));

        int tx = x + 16, tw = w - 32;
        int bx = tx;
        if (a.pinned()) {
            String pin = tr("blockland.activity.pinned");
            int pw = UiFont.width(pin) + 10;
            Draw.rect(g, bx, y + 12, pw, 12, ColorMath.fade(0x33FFD54F, alpha));
            Draw.text(g, pin, bx + 5, y + 14, 1f, ColorMath.fade(GOLD, alpha), false);
            bx += pw + 4;
        }
        ActivityStatus.badge(g, a, bx, y + 12, alpha);

        g.pose().pushMatrix();
        g.pose().translate(tx, y + 32);
        g.pose().scale(1.6f, 1.6f);
        Draw.text(g, Draw.fit(a.title(), tw / 1.6f), 0, 0, 1f, ColorMath.fade(TEXT, alpha), false);
        g.pose().popMatrix();

        List<FormattedCharSequence> summary = Draw.wrap(a.summary(), tw);
        float sy = y + 56;
        for (FormattedCharSequence line : summary.subList(0, Math.min(2, summary.size()))) {
            Draw.text(g, line, tx, sy, ColorMath.fade(MUTED, alpha));
            sy += UiFont.LINE_HEIGHT + 2;
        }

        String range = ActivityStatus.range(a);
        String when = range.isEmpty() ? ActivityStatus.line(a) : tr("blockland.activity.time", range) + "   " + ActivityStatus.line(a);
        Draw.text(g, Draw.fit(when, tw), tx, y + HERO_H - 18, 1f, ColorMath.fade(DIM, alpha), false);
    }

    // ---- input ---------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY, backX, backY, backW, backH)) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            onClose();
            return true;
        }
        if (body != null && body.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (body != null && body.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
