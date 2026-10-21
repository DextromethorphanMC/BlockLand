package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/** Logo, tabs with a spring-driven underline, and the player's playtime and the online count. */
public final class TopBar {
    public static final int HEIGHT = 32;
    private static final int PAD = 16;

    private final Font font;
    private final List<String> tabs;
    private final int[] tabX;
    private final Spring[] tabHover;
    private final Spring indicatorX = new Spring(0).config(260, 24);
    private final Spring indicatorW = new Spring(0).config(260, 24);
    private final AnimValue slide = new AnimValue(0);
    private boolean placed;

    public TopBar(Font font, List<String> tabs) {
        this.font = font;
        this.tabs = tabs;
        this.tabX = new int[tabs.size()];
        this.tabHover = new Spring[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) tabHover[i] = new Spring(0).config(320, 26);
    }

    public void playIntro() {
        Anim.fromTo(slide, -HEIGHT, 0, 0.6).ease(Ease.EXPO_OUT);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int width, int activeTab) {
        g.pose().pushMatrix();
        g.pose().translate(0, slide.get());
        g.fill(0, 0, width, HEIGHT, 0xE605070B);
        Draw.fadeY(g, 0, HEIGHT, width, 10, 0x60000000, true);
        g.fill(0, HEIGHT - 1, width, HEIGHT, LINE);

        Draw.text(g, font, "BLOCK", PAD, 9, 1.6f, TEXT, true);
        Draw.text(g, font, "LAND", PAD + font.width("BLOCK") * 1.6f + 1, 9, 1.6f, ACCENT, true);

        int tx = PAD + (int) (font.width("BLOCKLAND") * 1.6f) + 30;
        for (int i = 0; i < tabs.size(); i++) {
            String label = tabs.get(i);
            tabX[i] = tx;
            boolean hovered = mouseY >= 0 && mouseY < HEIGHT && mouseX >= tx - 6 && mouseX < tx + font.width(label) + 6;
            tabHover[i].setTarget(hovered || i == activeTab ? 1 : 0);
            float hv = tabHover[i].get();
            if (hovered && i != activeTab) Draw.rect(g, tx - 6, 6, font.width(label) + 12, HEIGHT - 12, ColorMath.fade(0x14FFFFFF, hv));
            Draw.text(g, font, label, tx, 12 - hv, 1f, ColorMath.mix(MUTED, TEXT, hv), false);
            tx += font.width(label) + 24;
        }
        float targetX = tabX[activeTab];
        float targetW = font.width(tabs.get(activeTab));
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

        Minecraft mc = Minecraft.getInstance();
        int online = mc.getConnection() == null ? 0 : mc.getConnection().getOnlinePlayers().size();
        String onlineText = String.valueOf(online);
        int rx = width - PAD - font.width(onlineText);
        g.drawString(font, onlineText, rx, 12, TEXT, false);
        rx -= font.width("在线玩家 ");
        g.drawString(font, "在线玩家 ", rx, 12, MUTED, false);
        Draw.glow(g, rx - 7, 15.5f, 4, 4, ColorMath.fade(OK, 0.7f));
        g.fill(rx - 9, 14, rx - 5, 18, OK);

        String playtime = formatPlaytime(ClientLobbyState.playtimeSeconds());
        rx -= 22 + font.width(playtime);
        g.drawString(font, playtime, rx, 12, TEXT, false);
        rx -= font.width("总游玩时间 ");
        g.drawString(font, "总游玩时间 ", rx, 12, MUTED, false);
        g.fill(rx + font.width("总游玩时间 ") + font.width(playtime) + 8, 11, rx + font.width("总游玩时间 ") + font.width(playtime) + 9, 21, LINE);
        g.pose().popMatrix();
    }

    /** @return the clicked tab index, or -1 */
    public int tabAt(double mouseX, double mouseY) {
        if (mouseY < 0 || mouseY >= HEIGHT) return -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (mouseX >= tabX[i] - 6 && mouseX < tabX[i] + font.width(tabs.get(i)) + 6) return i;
        }
        return -1;
    }

    private static String formatPlaytime(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return hours > 0 ? hours + " 小时 " + minutes + " 分" : minutes + " 分钟";
    }
}
