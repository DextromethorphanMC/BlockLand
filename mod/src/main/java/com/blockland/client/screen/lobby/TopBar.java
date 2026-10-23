package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * Logo, tabs with a spring-driven underline (and unread badges), the player's playtime and the online count, and the
 * lobby's own buttons on the right: settings, enter the world (operators only) and leave the server — the lobby has
 * no pause menu.
 */
public final class TopBar {
    public static final int HEIGHT = 32;
    private static final int PAD = 16;

    public enum Button { NONE, SETTINGS, WORLD, QUIT }

    private final List<String> tabKeys;
    private final int[] tabX;
    private final int[] tabW;
    private final Spring[] tabHover;
    private final Spring indicatorX = new Spring(0).config(260, 24);
    private final Spring indicatorW = new Spring(0).config(260, 24);
    private final AnimValue slide = new AnimValue(0);
    private final List<ButtonHit> buttons = new ArrayList<>();
    private final Spring[] buttonHover = {new Spring(0).config(320, 26), new Spring(0).config(320, 26), new Spring(0).config(320, 26)};
    private boolean placed;

    private record ButtonHit(Button button, int x, int y, int w, int h) {
    }

    /** @param tabKeys translation keys of the tab labels */
    public TopBar(List<String> tabKeys) {
        this.tabKeys = tabKeys;
        this.tabX = new int[tabKeys.size()];
        this.tabW = new int[tabKeys.size()];
        this.tabHover = new Spring[tabKeys.size()];
        for (int i = 0; i < tabKeys.size(); i++) tabHover[i] = new Spring(0).config(320, 26);
    }

    public void playIntro() {
        Anim.fromTo(slide, -HEIGHT, 0, 0.6).ease(Ease.EXPO_OUT);
    }

    /** @param badges per tab, a number shown next to the label (0 = none) */
    public void render(GuiGraphics g, int mouseX, int mouseY, int width, int activeTab, int[] badges) {
        g.pose().pushMatrix();
        g.pose().translate(0, slide.get());
        g.fill(0, 0, width, HEIGHT, 0xE605070B);
        Draw.fadeY(g, 0, HEIGHT, width, 10, 0x60000000, true);
        g.fill(0, HEIGHT - 1, width, HEIGHT, LINE);

        Draw.text(g, "BLOCK", PAD, 9, 1.6f, TEXT, true);
        Draw.text(g, "LAND", PAD + UiFont.width("BLOCK") * 1.6f + 1, 9, 1.6f, ACCENT, true);

        int tx = PAD + (int) (UiFont.width("BLOCKLAND") * 1.6f) + 30;
        for (int i = 0; i < tabKeys.size(); i++) {
            String label = tr(tabKeys.get(i));
            int badge = i < badges.length ? badges[i] : 0;
            String badgeText = badge > 99 ? "99+" : String.valueOf(badge);
            int width1 = UiFont.width(label) + (badge > 0 ? UiFont.width(badgeText) + 8 : 0);
            tabX[i] = tx;
            tabW[i] = width1;
            boolean hovered = mouseY >= 0 && mouseY < HEIGHT && mouseX >= tx - 6 && mouseX < tx + width1 + 6;
            tabHover[i].setTarget(hovered || i == activeTab ? 1 : 0);
            float hv = tabHover[i].get();
            if (hovered && i != activeTab) Draw.rect(g, tx - 6, 6, width1 + 12, HEIGHT - 12, ColorMath.fade(0x14FFFFFF, hv));
            Draw.text(g, label, tx, 12 - hv, 1f, ColorMath.mix(MUTED, TEXT, hv), false);
            if (badge > 0) {
                int bx = tx + UiFont.width(label) + 3, bw = UiFont.width(badgeText) + 4;
                Draw.rect(g, bx, 9, bw, 10, ERROR);
                Draw.text(g, badgeText, bx + 2, 10, 1f, TEXT, false);
            }
            tx += width1 + 24;
        }
        float targetX = tabX[activeTab];
        float targetW = tabW[activeTab];
        if (!placed) {
            indicatorX.set(targetX);
            indicatorW.set(targetW);
            placed = true;
        }
        indicatorX.setTarget(targetX);
        indicatorW.setTarget(targetW);
        int ix = Math.round(indicatorX.get()), iw = Math.round(indicatorW.get());
        Draw.glow(g, ix + iw / 2f, HEIGHT - 2, iw * 0.9f, 6, ColorMath.fade(ACCENT, 0.45f));
        g.fill(ix, HEIGHT - 3, ix + iw, HEIGHT - 1, ACCENT);

        // right side: buttons, then online count and playtime
        buttons.clear();
        int rx = width - PAD;
        rx = button(g, mouseX, mouseY, rx, Button.QUIT, tr("blockland.lobby.quit"), 0, CANCEL);
        if (ClientLobbyState.canEnterWorld()) rx = button(g, mouseX, mouseY, rx, Button.WORLD, tr("blockland.lobby.enter_world"), 1, INFO);
        rx = button(g, mouseX, mouseY, rx, Button.SETTINGS, "⚙ " + tr("blockland.lobby.settings"), 2, ACCENT);
        rx -= 10;
        g.fill(rx, 11, rx + 1, 21, LINE);
        rx -= 10;

        String onlineText = String.valueOf(ClientLobbyState.online());
        rx -= UiFont.width(onlineText);
        UiFont.draw(g, onlineText, rx, 12, TEXT, false);
        String onlineLabel = tr("blockland.lobby.online") + " ";
        rx -= UiFont.width(onlineLabel);
        UiFont.draw(g, onlineLabel, rx, 12, MUTED, false);
        Draw.glow(g, rx - 7, 15.5f, 4, 4, ColorMath.fade(OK, 0.7f));
        g.fill(rx - 9, 14, rx - 5, 18, OK);

        String playtime = formatPlaytime(ClientLobbyState.playtimeSeconds());
        String playLabel = tr("blockland.lobby.playtime") + " ";
        rx -= 22 + UiFont.width(playtime);
        UiFont.draw(g, playtime, rx, 12, TEXT, false);
        rx -= UiFont.width(playLabel);
        UiFont.draw(g, playLabel, rx, 12, MUTED, false);
        g.fill(rx + UiFont.width(playLabel) + UiFont.width(playtime) + 8, 11, rx + UiFont.width(playLabel) + UiFont.width(playtime) + 9, 21, LINE);
        g.pose().popMatrix();
    }

    /** A small pill button ending at {@code right}; returns the x where the next item must end. */
    private int button(GuiGraphics g, int mouseX, int mouseY, int right, Button button, String label, int springIndex, int color) {
        int w = UiFont.width(label) + 14, h = 16, x = right - w, y = 8;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        Spring spring = buttonHover[springIndex];
        spring.setTarget(hovered ? 1 : 0);
        float hv = spring.get();
        Draw.rect(g, x, y, w, h, ColorMath.mix(0xFF141C29, ColorMath.withAlpha(color, 0xFF), 0.18f + 0.3f * hv));
        Draw.outline(g, x, y, w, h, ColorMath.fade(color, 0.35f + 0.5f * hv));
        Draw.text(g, label, x + 7, y + 4, 1f, ColorMath.mix(TEXT, 0xFFFFFFFF, hv), false);
        buttons.add(new ButtonHit(button, x, y, w, h));
        return x - 6;
    }

    /** @return the clicked tab index, or -1 */
    public int tabAt(double mouseX, double mouseY) {
        if (mouseY < 0 || mouseY >= HEIGHT) return -1;
        for (int i = 0; i < tabKeys.size(); i++) {
            if (mouseX >= tabX[i] - 6 && mouseX < tabX[i] + tabW[i] + 6) return i;
        }
        return -1;
    }

    public Button buttonAt(double mouseX, double mouseY) {
        for (ButtonHit hit : buttons) {
            if (mouseX >= hit.x && mouseX < hit.x + hit.w && mouseY >= hit.y && mouseY < hit.y + hit.h) return hit.button;
        }
        return Button.NONE;
    }

    private static String formatPlaytime(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return hours > 0 ? tr("blockland.lobby.playtime_hours", hours, minutes) : tr("blockland.lobby.playtime_minutes", minutes);
    }
}
