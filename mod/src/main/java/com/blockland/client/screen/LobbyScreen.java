package com.blockland.client.screen;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientKitLayouts;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.screen.lobby.CharacterStage;
import com.blockland.client.screen.lobby.MatchPanel;
import com.blockland.client.screen.lobby.ModeBrowser;
import com.blockland.client.screen.lobby.PartyPanel;
import com.blockland.client.screen.lobby.TopBar;
import com.blockland.client.ui.Draw;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.C2SJoinQueue;
import com.blockland.network.packet.C2SLeaveQueue;
import com.blockland.network.packet.LobbyModeEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/**
 * Main lobby screen, laid out like the CS2 main menu: tabs on top ("开始游戏" / "商店"); the play tab has the
 * mode browser on the left, the player's character in the middle and the matchmaking panel bottom right.
 * This class only lays out and wires the components in {@code lobby}; all motion runs on {@link Ticker}.
 */
public final class LobbyScreen extends Screen {
    private static final int TOP_BAR = TopBar.HEIGHT;
    private static final int PAD = 16;

    private enum Tab {
        PLAY("开始游戏"), SHOP("商店");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static String selectedModeId = "";
    private static Tab tab = Tab.PLAY;

    private ShopPanel shop;
    private TopBar topBar;
    private ModeBrowser browser;
    private MatchPanel match;
    private CharacterStage stage;
    private PartyPanel party;
    private boolean introPlayed;
    private boolean switching;
    private final AnimValue contentAlpha = new AnimValue(1);
    private final AnimValue contentShift = new AnimValue(0);
    private final AnimValue columnIn = new AnimValue(1);
    private final AnimValue panelIn = new AnimValue(1);

    // layout, recomputed every frame from the screen size
    private int listX, listY, listW, listH;
    private int btnX, btnY, btnW, btnH;
    private int infoY, infoH;
    private int charLeft, charRight, charTop, charBottom;
    private boolean nameAbove;

    public LobbyScreen() {
        super(Component.literal("BlockLand 大厅"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        layout();
        if (shop == null) {
            shop = new ShopPanel(font);
            topBar = new TopBar(font, List.of(Tab.PLAY.label, Tab.SHOP.label));
            browser = new ModeBrowser(font);
            match = new MatchPanel(font);
            stage = new CharacterStage(font);
            party = new PartyPanel(font);
        }
        if (tab == Tab.SHOP) shop.open();
        if (!introPlayed) {
            // init() also runs on resize and when returning from the loadout editor: animate the first open only
            introPlayed = true;
            topBar.playIntro();
            browser.playIntro(0.12);
            stage.playIntro(0.2);
            Anim.fromTo(columnIn, 0, 1, 0.5).ease(Ease.CUBIC_OUT);
            Anim.fromTo(panelIn, 0, 1, 0.6).ease(Ease.BACK_OUT).delay(0.3);
        }
    }

    private void layout() {
        listX = PAD + 4;
        listY = TOP_BAR + 44;
        listW = Math.min(340, (int) (width * 0.42));
        listH = height - listY - PAD - 18;
        btnW = Math.min(280, width - listX - listW - PAD * 3);
        btnH = 40;
        btnX = width - PAD - btnW;
        btnY = height - PAD - btnH;
        infoH = 60;
        infoY = btnY - infoH - 8;

        charLeft = listX + listW + PAD;
        charRight = width - PAD;
        nameAbove = charRight - charLeft < btnW + 160;
        if (nameAbove) {
            // narrow screen: name on top, the model's feet just behind the top of the match panel
            charTop = TOP_BAR + 34;
            charBottom = infoY + 12;
        } else {
            // wide screen: character stands left of the match panel, full height (CS2 style)
            charTop = TOP_BAR + 8;
            charRight = btnX - PAD;
            charBottom = height - PAD - 34;
        }
    }

    // ---- rendering -----------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        layout();
        g.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
        // slow drifting colour orbs give the backdrop depth without competing with content
        double t = Ticker.global().time();
        Draw.glow(g, (float) (width * (0.72 + 0.05 * Math.sin(t * 0.21))), (float) (height * (0.38 + 0.06 * Math.cos(t * 0.17))),
                width * 0.42f, height * 0.55f, 0x2E3A6EA5);
        Draw.glow(g, (float) (width * (0.12 + 0.04 * Math.cos(t * 0.15))), (float) (height * (0.85 + 0.05 * Math.sin(t * 0.19))),
                width * 0.35f, height * 0.4f, 0x1CFFB02E);
        Draw.glow(g, (float) (width * (0.45 + 0.06 * Math.sin(t * 0.11))), (float) (height * 0.05), width * 0.3f, height * 0.25f, 0x165EC8FF);
        Draw.fadeY(g, 0, height - 70, width, 70, 0x70000000, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        ensureSelection();
        super.render(g, mouseX, mouseY, partialTick);

        float alpha = contentAlpha.get();
        g.pose().pushMatrix();
        g.pose().translate(0, contentShift.get());
        if (tab == Tab.SHOP) {
            shop.render(g, mouseX, mouseY, PAD, TOP_BAR + PAD, width - PAD * 2, height - TOP_BAR - PAD * 2 - 4);
        } else {
            renderPlay(g, mouseX, mouseY, alpha);
        }
        g.pose().popMatrix();
        if (tab == Tab.SHOP && alpha < 1) g.fill(0, TOP_BAR, width, height, ColorMath.fade(BG_BOTTOM, 1 - alpha));

        topBar.render(g, mouseX, mouseY, width, tab.ordinal());
    }

    private void renderPlay(GuiGraphics g, int mouseX, int mouseY, float alpha) {
        stage.render(g, charLeft, charRight, charTop, charBottom, nameAbove, TOP_BAR + 8, party.left() - 8);

        // mode column
        float column = alpha * columnIn.get();
        g.pose().pushMatrix();
        g.pose().translate((1 - columnIn.get()) * -24, 0);
        int panelTop = listY - 32;
        Draw.rect(g, listX - 10, panelTop, listW + 20, listY + listH + 8 - panelTop, ColorMath.fade(PANEL, column));
        Draw.outline(g, listX - 10, panelTop, listW + 20, listY + listH + 8 - panelTop, ColorMath.fade(LINE, column));
        g.fill(listX, listY - 22, listX + 3, listY - 12, ColorMath.fade(ACCENT, column));
        Draw.text(g, font, "选择游戏模式", listX + 8, listY - 21, 1f, ColorMath.fade(TEXT, column), false);
        String count = ClientLobbyState.modes().size() + " 个模式";
        Draw.text(g, font, count, listX + listW - font.width(count), listY - 21, 1f, ColorMath.fade(DIM, column), false);
        if (ClientLobbyState.modes().isEmpty()) {
            Draw.text(g, font, "暂无可用的游戏模式", listX + 4, listY + 8, 1f, ColorMath.fade(MUTED, column), false);
            Draw.text(g, font, "请在后台「小游戏管理」中添加", listX + 4, listY + 22, 1f, ColorMath.fade(DIM, column), false);
        } else {
            browser.render(g, mouseX, mouseY, listX, listY, listW, listH, selectedModeId, column);
        }
        g.pose().popMatrix();

        // match panel rises in
        float p = panelIn.get();
        g.pose().pushMatrix();
        g.pose().translate(0, (1 - p) * 40);
        LobbyModeEntry selected = ClientLobbyState.mode(selectedModeId).orElse(null);
        match.render(g, mouseX, mouseY, btnX, infoY, btnW, infoH, btnY, btnH, selected, alpha * Math.min(1, Math.max(0, p)));
        g.pose().popMatrix();

        // party strip and invitations float above everything else in the right column
        party.render(g, mouseX, mouseY, width - PAD, TOP_BAR + 8, alpha);

        String hint = browser.isInGroup() ? "ESC 返回上级  ·  M 打开大厅  ·  /bl leave 返回大厅" : "ESC 关闭  ·  M 打开大厅  ·  /bl leave 返回大厅";
        Draw.text(g, font, hint, PAD, height - PAD + 2, 1f, ColorMath.fade(DIM, alpha), false);
    }

    // ---- input ---------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int clickedTab = topBar.tabAt(mouseX, mouseY);
            if (clickedTab >= 0) {
                if (clickedTab != tab.ordinal()) switchTab(Tab.values()[clickedTab]);
                return true;
            }
        }
        if (switching) return true;
        if (tab == Tab.SHOP) {
            return button == 0 && shop.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (party.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            layout();
            switch (match.mouseClicked(mouseX, mouseY)) {
                case MATCH -> {
                    onMatchButton();
                    return true;
                }
                case KIT -> {
                    LobbyModeEntry selected = ClientLobbyState.mode(selectedModeId).orElse(null);
                    if (selected != null) {
                        click();
                        minecraft.setScreen(new KitLayoutScreen(this, selected.id(), selected.title()));
                    }
                    return true;
                }
                case NONE -> {
                }
            }
            if (browser.mouseClicked(mouseX, mouseY, id -> {
                if (!ClientLobbyState.isQueued()) selectedModeId = id;
            }) == ModeBrowser.Result.SELECTED) {
                click();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        browser.mouseReleased();
        match.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.SHOP) return shop.mouseScrolled(scrollY);
        return browser.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private void switchTab(Tab next) {
        if (switching) return;
        switching = true;
        click();
        Anim.timeline()
                .add(Anim.to(contentAlpha, 0, 0.12).ease(Ease.QUAD_IN), 0)
                .add(Anim.to(contentShift, 10, 0.12).ease(Ease.QUAD_IN), 0)
                .call(() -> {
                    tab = next;
                    if (next == Tab.SHOP) shop.open();
                    contentShift.snap(-14);
                }, ">")
                .add(Anim.to(contentAlpha, 1, 0.25).ease(Ease.QUAD_OUT), ">")
                .add(Anim.to(contentShift, 0, 0.45).ease(Ease.EXPO_OUT), "<")
                .call(() -> switching = false, "<0.1");
    }

    private void onMatchButton() {
        if (ClientLobbyState.inGame()) return;
        boolean leader = minecraft.player == null || ClientLobbyState.isPartyLeader(minecraft.player.getUUID());
        if (ClientLobbyState.isQueued()) {
            BLNetwork.sendToServer(C2SLeaveQueue.INSTANCE);
            click();
            return;
        }
        if (!leader) return; // party members wait for the leader
        ClientLobbyState.mode(selectedModeId).filter(LobbyModeEntry::available).ifPresent(mode -> {
            // the locally saved kit arrangement travels with the queue request; the server validates it
            ClientKitLayouts.Layout layout = mode.hasKit() ? ClientKitLayouts.any(mode.id()).orElse(null) : null;
            BLNetwork.sendToServer(layout == null ? new C2SJoinQueue(mode.id(), "", List.of())
                    : new C2SJoinQueue(mode.id(), layout.signature(), layout.slots()));
            click();
        });
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (tab == Tab.PLAY && party.keyPressed(key, scanCode, modifiers)) return true;
        if (key == 256) {
            if (tab == Tab.SHOP && shop.isModalOpen()) {
                shop.closeModal();
                return true;
            }
            if (tab == Tab.PLAY && browser.back()) {
                click();
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (tab == Tab.PLAY && party.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    // ---- helpers -------------------------------------------------------------------------

    private void ensureSelection() {
        if (ClientLobbyState.isQueued()) {
            selectedModeId = ClientLobbyState.queuedModeId();
            return;
        }
        if (ClientLobbyState.mode(selectedModeId).isPresent()) return;
        List<LobbyModeEntry> candidates = browser.visibleModes();
        if (candidates.isEmpty()) candidates = ClientLobbyState.modes();
        if (candidates.isEmpty()) return;
        selectedModeId = candidates.stream().filter(LobbyModeEntry::available).findFirst().orElse(candidates.get(0)).id();
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
