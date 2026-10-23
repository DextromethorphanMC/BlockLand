package com.blockland.client.screen.pause;

import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.settings.SettingsScreen;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * BlockLand's own Esc menu in the world, in place of vanilla's pause screen ({@link PauseMenus} swaps it in): the game
 * stays visible behind a dark band on the left with a column of large entries — back to the game, settings, leave
 * the match (or back to the lobby for an operator standing in the world), vanilla's own menu (options, advancements,
 * mods…) and disconnect. Mouse or keyboard (up / down / Enter, Esc closes). Pauses a single-player world like vanilla.
 */
public final class GameMenuScreen extends Screen {
    private static final int PAD = 28;
    private static final int ENTRY_H = 26;
    /** Seconds a destructive entry waits for its confirming second click. */
    private static final double CONFIRM_SECONDS = 3;

    private record Entry(Supplier<String> label, String hint, Runnable action, boolean danger, boolean confirm) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Spring> hover = new ArrayList<>();
    private final AnimValue intro = new AnimValue(0);
    private int selected = -1;
    private int listTop;
    private int armed = -1;
    private double armedUntil;

    public GameMenuScreen() {
        super(Component.translatable("blockland.pause.title"));
    }

    @Override
    protected void init() {
        if (intro.get() == 0) intro.to(1, 0.32, Ease.QUART_OUT);
        entries.clear();
        entries.add(new Entry(() -> tr("blockland.pause.resume"), "", this::onClose, false, false));
        entries.add(new Entry(() -> tr("blockland.pause.settings"), tr("blockland.pause.settings.hint"),
                () -> minecraft.setScreen(new SettingsScreen(this)), false, false));
        if (ClientLobbyState.known()) {
            if (ClientLobbyState.inGame()) {
                entries.add(new Entry(() -> tr(armed >= 0 ? "blockland.pause.leave.confirm" : "blockland.pause.leave"),
                        tr("blockland.pause.leave.hint"), () -> command("bl leave"), true, true));
            } else {
                entries.add(new Entry(() -> tr("blockland.pause.lobby"), tr("blockland.pause.lobby.hint"), () -> command("bl lobby"),
                        false, false));
            }
        }
        entries.add(new Entry(() -> tr("blockland.pause.vanilla"), tr("blockland.pause.vanilla.hint"),
                () -> minecraft.setScreen(PauseMenus.vanilla()), false, false));
        Component disconnect = CommonComponents.disconnectButtonLabel(minecraft.isLocalServer());
        entries.add(new Entry(disconnect::getString, "", this::disconnect, true, false));
        while (hover.size() < entries.size()) hover.add(new Spring(0).config(340, 28));
    }

    private void command(String command) {
        minecraft.setScreen(null);
        if (minecraft.getConnection() != null) minecraft.getConnection().sendCommand(command);
    }

    private void disconnect() {
        minecraft.getReportingContext().draftReportHandled(minecraft, this,
                () -> PauseScreen.disconnectFromWorld(minecraft, ClientLevel.DEFAULT_QUIT_MESSAGE), true);
    }

    private void activate(int i) {
        if (i < 0 || i >= entries.size()) return;
        Entry entry = entries.get(i);
        click();
        if (entry.confirm() && armed != i) {
            armed = i;
            armedUntil = Ticker.global().time() + CONFIRM_SECONDS;
            return;
        }
        armed = -1;
        entry.action().run();
    }

    // ---- rendering -------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        renderBlurredBackground(g);
        float a = intro.get();
        int band = bandWidth();
        // a dark band on the left that fades into the game
        g.fill(0, 0, band, height, ColorMath.fade(0xE0060A11, a));
        Draw.fadeX(g, band, 0, Math.max(40, width / 3), height, ColorMath.fade(0xE0060A11, a), true);
        g.fillGradient(0, 0, width, height, ColorMath.fade(0x30000000, a), ColorMath.fade(0x70000000, a));
        Draw.glow(g, band * 0.35f, height * 0.3f, band * 0.9f, height * 0.5f, ColorMath.fade(0x1A3A6EA5, a));
    }

    private int bandWidth() {
        return Math.min(width, Math.max(220, Math.round(width * 0.32f)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (armed >= 0 && Ticker.global().time() > armedUntil) armed = -1;
        float a = intro.get();
        float slide = (1 - a) * -24;
        int x = Math.round(PAD + slide);

        // header: brand, title, where the player is
        int y = Math.max(PAD, height / 2 - (entries.size() * ENTRY_H) / 2 - 64);
        Draw.text(g, "BLOCKLAND", x, y, 1f, ColorMath.fade(ACCENT, a), false);
        Draw.text(g, tr("blockland.pause.title"), x, y + 12, 2.2f, ColorMath.fade(TEXT, a), true);
        String where = tr(!ClientLobbyState.known() ? "blockland.pause.where.world"
                : ClientLobbyState.inGame() ? "blockland.pause.where.match" : "blockland.pause.where.free");
        Draw.text(g, where, x, y + 36, 1f, ColorMath.fade(MUTED, a), false);
        g.fill(x, y + 50, x + Math.min(bandWidth() - PAD * 2, 180), y + 51, ColorMath.fade(LINE, a));

        // entries
        listTop = y + 62;
        int hovered = entryAt(mouseX, mouseY);
        if (hovered >= 0) selected = hovered;
        int w = bandWidth() - PAD * 2;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Spring spring = hover.get(i);
            spring.setTarget(i == selected ? 1 : 0);
            float h = spring.get();
            // entries arrive one after another
            float own = clamp01(a * 1.6f - i * 0.12f);
            int ey = listTop + i * ENTRY_H;
            int ex = Math.round(x + (1 - own) * -16 + h * 6);
            int base = entry.danger() ? ERROR : TEXT;
            int color = ColorMath.fade(ColorMath.mix(entry.danger() ? ColorMath.mix(MUTED, ERROR, 0.55f) : MUTED, base, h), own);
            if (h > 0.01f) {
                Draw.fadeX(g, x - 8, ey, Math.round(w * 0.9f * h) + 8, ENTRY_H - 4, ColorMath.fade(entry.danger() ? 0x33FF6B6B : 0x26FFB02E, own * h), true);
                g.fill(x - 8, ey, x - 6, ey + ENTRY_H - 4, ColorMath.fade(entry.danger() ? ERROR : ACCENT, own * h));
            }
            String label = UiFont.fit(entry.label().get(), (w - 10) / 1.4f);
            Draw.text(g, label, ex, ey + 4, 1.4f, color, false);
            if (i == armed) {
                double left = Math.max(0, armedUntil - Ticker.global().time()) / CONFIRM_SECONDS;
                g.fill(ex, ey + ENTRY_H - 6, ex + Math.round((w - 16) * (float) left), ey + ENTRY_H - 5, ColorMath.fade(ERROR, own));
            }
        }

        // the selected entry's hint, then the controls
        int below = listTop + entries.size() * ENTRY_H + 8;
        if (selected >= 0 && selected < entries.size() && !entries.get(selected).hint().isEmpty()) {
            for (var line : UiFont.split(entries.get(selected).hint(), w)) {
                Draw.text(g, line, x, below, ColorMath.fade(DIM, a));
                below += UiFont.LINE_HEIGHT + 2;
            }
        }
        String keys = tr("blockland.pause.keys");
        Draw.text(g, keys, x, height - PAD, 1f, ColorMath.fade(DIM, a), false);
        if (ClientLobbyState.known()) {
            String self = ClientLobbyState.selfName();
            String online = tr("blockland.pause.online", ClientLobbyState.online());
            Draw.text(g, self, width - PAD - UiFont.width(self), height - PAD - 12, 1f, ColorMath.fade(TEXT, a), true);
            Draw.text(g, online, width - PAD - UiFont.width(online), height - PAD, 1f, ColorMath.fade(MUTED, a), false);
        }
    }

    private int entryAt(double mouseX, double mouseY) {
        if (mouseX < 0 || mouseX > bandWidth() || mouseY < listTop) return -1;
        int i = (int) ((mouseY - listTop) / ENTRY_H);
        return i < entries.size() && mouseY - listTop - i * ENTRY_H < ENTRY_H - 4 ? i : -1;
    }

    // ---- input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int i = entryAt(mouseX, mouseY);
            if (i >= 0) {
                activate(i);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                selected = selected <= 0 ? entries.size() - 1 : selected - 1;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                selected = selected < 0 || selected >= entries.size() - 1 ? 0 : selected + 1;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                activate(Math.max(0, selected));
                return true;
            }
            default -> {
                return super.keyPressed(key, scanCode, modifiers);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(null);
        minecraft.mouseHandler.grabMouse();
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
