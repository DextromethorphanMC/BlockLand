package com.blockland.client.screen;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientAnnouncements;
import com.blockland.client.ClientKitLayouts;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.screen.lobby.ActivitiesPanel;
import com.blockland.client.screen.lobby.AnnouncementReminder;
import com.blockland.client.screen.lobby.AnnouncementsPanel;
import com.blockland.client.screen.lobby.CharacterStage;
import com.blockland.client.screen.lobby.CheckInPanel;
import com.blockland.client.screen.lobby.InventoryPanel;
import com.blockland.client.screen.lobby.LobbyTab;
import com.blockland.client.screen.lobby.ProfilePanel;
import com.blockland.client.screen.lobby.MatchPanel;
import com.blockland.client.screen.lobby.ModeBrowser;
import com.blockland.client.screen.lobby.PartyPanel;
import com.blockland.client.screen.lobby.TopBar;
import com.blockland.client.settings.SettingsScreen;
import com.blockland.client.ui.Draw;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.C2SJoinQueue;
import com.blockland.network.packet.C2SLeaveQueue;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.LobbyPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * Main lobby screen, laid out like the CS2 main menu: tabs on top ("开始游戏" / "活动" / "工匠" / "公告" / "商店" / "库存" / "个人主页"),
 * the lobby's own buttons top right (daily check-in, settings, leave). The play tab has the mode browser on the left, the player's character in
 * the middle, the party and unread announcements top right and the matchmaking panel bottom right.
 * <p>
 * The lobby is the configuration phase: there is no world behind this screen, so it cannot be closed — Escape only
 * steps back inside it. This class only lays out and wires the components in {@code lobby}; all motion runs on
 * {@link Ticker}.
 */
public final class LobbyScreen extends Screen {
    private static final int TOP_BAR = TopBar.HEIGHT;
    private static final int PAD = 16;

    private enum Tab {
        PLAY("blockland.lobby.tab.play"), EVENTS("blockland.lobby.tab.events"), CRAFT("blockland.lobby.tab.craft"), NEWS("blockland.lobby.tab.news"),
        SHOP("blockland.lobby.tab.shop"), INVENTORY("blockland.lobby.tab.inventory"), PROFILE("blockland.lobby.tab.profile");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    /** Makes the 工匠 (craft) tab; provided by the craft module, the lobby does not know it (without it the tab stays on 开始游戏). */
    @Nullable
    private static Supplier<LobbyTab> craftTabFactory;
    @Nullable
    private LobbyTab craft;
    private static String selectedModeId = "";
    private static Tab tab = Tab.PLAY;

    private ShopPanel shop;
    private InventoryPanel inventory;
    private ProfilePanel profile;
    private TopBar topBar;
    private ModeBrowser browser;
    private MatchPanel match;
    private CharacterStage stage;
    private PartyPanel party;
    private AnnouncementsPanel news;
    private ActivitiesPanel events;
    private AnnouncementReminder reminder;
    private CheckInPanel checkIn;
    private boolean introPlayed;
    private boolean switching;
    private final AnimValue contentAlpha = new AnimValue(1);
    private final AnimValue contentShift = new AnimValue(0);
    private final AnimValue columnIn = new AnimValue(1);
    private final AnimValue panelIn = new AnimValue(1);

    // layout, recomputed every frame from the screen size
    private int listX, listY, listW, listH;
    private int btnX, btnY, btnW, btnH;
    private int infoY, infoH, mapY;
    private int charLeft, charRight, charTop, charBottom;
    private boolean nameAbove;

    public LobbyScreen() {
        super(Component.translatable("blockland.lobby.title"));
    }

    public static void setCraftTab(@Nullable Supplier<LobbyTab> factory) {
        craftTabFactory = factory;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Nothing lies behind the lobby while it is the configuration phase (an operator in the world may close it). */
    @Override
    public boolean shouldCloseOnEsc() {
        return minecraft != null && minecraft.level != null;
    }

    @Override
    protected void init() {
        layout();
        if (shop == null) {
            shop = new ShopPanel();
            topBar = new TopBar(List.of(Tab.PLAY.key, Tab.EVENTS.key, Tab.CRAFT.key, Tab.NEWS.key, Tab.SHOP.key, Tab.INVENTORY.key, Tab.PROFILE.key));
            inventory = new InventoryPanel();
            profile = new ProfilePanel();
            craft = craftTabFactory == null ? null : craftTabFactory.get();
            browser = new ModeBrowser();
            match = new MatchPanel();
            stage = new CharacterStage();
            party = new PartyPanel(font);
            news = new AnnouncementsPanel();
            // each activity has its own screen; coming back returns to this tab
            events = new ActivitiesPanel(id -> {
                click();
                minecraft.setScreen(new ActivityScreen(this, id));
            });
            reminder = new AnnouncementReminder(id -> {
                news.select(id);
                if (tab != Tab.NEWS) switchTab(Tab.NEWS);
            });
            // 每日签到: opened from the top bar, over everything
            checkIn = new CheckInPanel();
        }
        if (tab == Tab.SHOP) shop.open();
        if (tab == Tab.INVENTORY) inventory.open();
        if (tab == Tab.PROFILE) profile.open();
        if (tab == Tab.EVENTS) events.open();
        if (tab == Tab.CRAFT) {
            if (craft == null) tab = Tab.PLAY;
            else craft.open();
        }
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
        mapY = btnY - MatchPanel.MAP_H - 6;
        infoH = 60;
        infoY = mapY - infoH - 6;

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
        switch (tab) {
            case SHOP -> shop.render(g, mouseX, mouseY, PAD, TOP_BAR + PAD, width - PAD * 2, height - TOP_BAR - PAD * 2 - 4);
            case INVENTORY -> inventory.render(g, mouseX, mouseY, PAD + 4, TOP_BAR + PAD, width - PAD * 2 - 8, height - TOP_BAR - PAD * 2 - 4, alpha);
            case PROFILE -> profile.render(g, mouseX, mouseY, PAD + 4, TOP_BAR + PAD, width - PAD * 2 - 8, height - TOP_BAR - PAD * 2 - 4, alpha);
            case NEWS -> news.render(g, mouseX, mouseY, PAD + 4, TOP_BAR + PAD, width - PAD * 2 - 8, height - TOP_BAR - PAD * 2 - 4, alpha);
            case EVENTS -> events.render(g, mouseX, mouseY, PAD + 4, TOP_BAR + PAD, width - PAD * 2 - 8, height - TOP_BAR - PAD * 2 - 4, alpha);
            case CRAFT -> {
                if (craft != null) craft.render(g, mouseX, mouseY, PAD + 4, TOP_BAR + PAD, width - PAD * 2 - 8, height - TOP_BAR - PAD * 2 - 4, alpha);
            }
            default -> renderPlay(g, mouseX, mouseY, alpha);
        }
        g.pose().popMatrix();
        if (tab != Tab.PLAY && alpha < 1) g.fill(0, TOP_BAR, width, height, ColorMath.fade(BG_BOTTOM, 1 - alpha));

        int[] badges = new int[Tab.values().length];
        badges[Tab.NEWS.ordinal()] = ClientAnnouncements.unread().size();
        topBar.setCheckInPending(checkIn.pending());
        boolean modal = checkIn.isOpen();
        topBar.render(g, modal ? -1 : mouseX, modal ? -1 : mouseY, width, tab.ordinal(), badges);
        checkIn.render(g, mouseX, mouseY, width, height);
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
        Draw.text(g, tr("blockland.lobby.pick_mode"), listX + 8, listY - 21, 1f, ColorMath.fade(TEXT, column), false);
        String count = tr("blockland.lobby.mode_count", ClientLobbyState.modes().size());
        Draw.text(g, count, listX + listW - UiFont.width(count), listY - 21, 1f, ColorMath.fade(DIM, column), false);
        if (ClientLobbyState.modes().isEmpty()) {
            Draw.text(g, tr("blockland.lobby.no_modes"), listX + 4, listY + 8, 1f, ColorMath.fade(MUTED, column), false);
            Draw.text(g, tr("blockland.lobby.no_modes_hint"), listX + 4, listY + 22, 1f, ColorMath.fade(DIM, column), false);
        } else {
            browser.render(g, mouseX, mouseY, listX, listY, listW, listH, selectedModeId, column);
        }
        g.pose().popMatrix();

        // party strip and invitations top right, unread announcements under them
        party.render(g, mouseX, mouseY, width - PAD, TOP_BAR + 8, alpha);
        int reminderTop = Math.max(TOP_BAR + 36, party.bottom() + 8);
        reminder.render(g, mouseX, mouseY, width - PAD, reminderTop, infoY - 10, alpha);

        // match panel rises in
        float p = panelIn.get();
        g.pose().pushMatrix();
        g.pose().translate(0, (1 - p) * 40);
        LobbyModeEntry selected = ClientLobbyState.mode(selectedModeId).orElse(null);
        match.render(g, mouseX, mouseY, btnX, infoY, btnW, infoH, mapY, btnY, btnH, selected, alpha * Math.min(1, Math.max(0, p)));
        g.pose().popMatrix();

        String hint = tr(browser.isInGroup() ? "blockland.lobby.hint_group" : "blockland.lobby.hint");
        Draw.text(g, hint, PAD, height - PAD + 2, 1f, ColorMath.fade(DIM, alpha), false);
    }

    // ---- input ---------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (checkIn.isOpen()) return button != 0 || checkIn.mouseClicked(mouseX, mouseY);
        if (button == 0) {
            int clickedTab = topBar.tabAt(mouseX, mouseY);
            if (clickedTab >= 0) {
                if (clickedTab != tab.ordinal()) switchTab(Tab.values()[clickedTab]);
                return true;
            }
            switch (topBar.buttonAt(mouseX, mouseY)) {
                case SETTINGS -> {
                    click();
                    minecraft.setScreen(new SettingsScreen(this));
                    return true;
                }
                case WORLD -> {
                    click();
                    BLNetwork.sendToServer(LobbyPackets.C2SEnterWorld.INSTANCE);
                    return true;
                }
                case QUIT -> {
                    click();
                    quit();
                    return true;
                }
                case CHECK_IN -> {
                    click();
                    checkIn.open();
                    return true;
                }
                case NONE -> {
                }
            }
        }
        if (switching) return true;
        if (tab == Tab.INVENTORY) {
            return button == 0 && inventory.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (tab == Tab.CRAFT && craft != null) {
            return craft.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (tab == Tab.PROFILE) {
            return button == 0 && profile.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (tab == Tab.SHOP) {
            return button == 0 && shop.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (tab == Tab.NEWS) {
            return button == 0 && news.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
        }
        if (tab == Tab.EVENTS) {
            return button == 0 && events.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
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
                case CONSUMED -> {
                    click();
                    return true;
                }
                case NONE -> {
                }
            }
            if (reminder.mouseClicked(mouseX, mouseY)) {
                click();
                return true;
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
        if (tab == Tab.CRAFT && craft != null) craft.mouseReleased(mouseX, mouseY, button);
        browser.mouseReleased();
        match.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (tab == Tab.CRAFT && craft != null && craft.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (checkIn.isOpen()) return true;
        if (tab == Tab.SHOP) return shop.mouseScrolled(scrollY);
        if (tab == Tab.CRAFT) return craft != null && craft.mouseScrolled(mouseX, mouseY, scrollY);
        if (tab == Tab.INVENTORY) return inventory.mouseScrolled(mouseX, mouseY, scrollY);
        if (tab == Tab.PROFILE) return profile.mouseScrolled(mouseX, mouseY, scrollY);
        if (tab == Tab.NEWS) return news.mouseScrolled(mouseX, mouseY, scrollY);
        if (tab == Tab.EVENTS) return events.mouseScrolled(mouseX, mouseY, scrollY);
        return browser.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private void switchTab(Tab next) {
        if (switching) return;
        if (next == Tab.CRAFT && craft == null) return;
        switching = true;
        click();
        Anim.timeline()
                .add(Anim.to(contentAlpha, 0, 0.12).ease(Ease.QUAD_IN), 0)
                .add(Anim.to(contentShift, 10, 0.12).ease(Ease.QUAD_IN), 0)
                .call(() -> {
                    tab = next;
                    if (next == Tab.SHOP) shop.open();
                    if (next == Tab.INVENTORY) inventory.open();
                    if (next == Tab.PROFILE) profile.open();
                    if (next == Tab.EVENTS) events.open();
                    if (next == Tab.CRAFT && craft != null) craft.open();
                    contentShift.snap(-14);
                }, ">")
                .add(Anim.to(contentAlpha, 1, 0.25).ease(Ease.QUAD_OUT), ">")
                .add(Anim.to(contentShift, 0, 0.45).ease(Ease.EXPO_OUT), "<")
                .call(() -> switching = false, "<0.1");
    }

    private void onMatchButton() {
        if (ClientLobbyState.inGame()) return;
        if (ClientLobbyState.isQueued()) {
            BLNetwork.sendToServer(C2SLeaveQueue.INSTANCE);
            click();
            return;
        }
        if (!ClientLobbyState.isLeader()) return; // party members wait for the leader
        ClientLobbyState.mode(selectedModeId).filter(LobbyModeEntry::available).ifPresent(mode -> {
            // the locally saved kit arrangement travels with the queue request; the server validates it (and the map)
            ClientKitLayouts.Layout layout = mode.hasKit() ? ClientKitLayouts.any(mode.id()).orElse(null) : null;
            String map = ClientLobbyState.chosenMap(mode.id());
            BLNetwork.sendToServer(layout == null ? new C2SJoinQueue(mode.id(), map, "", List.of())
                    : new C2SJoinQueue(mode.id(), map, layout.signature(), layout.slots()));
            click();
        });
    }

    /** Leaves the server. In the lobby there is no pause menu, so this is the way out. */
    private void quit() {
        Minecraft mc = minecraft;
        if (mc.level != null) {
            mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
            return;
        }
        Connection connection = BLNetwork.clientConnection();
        // nobody may tick the closed connection afterwards: vanilla would then show "disconnected" over the server list
        BLNetwork.forgetClientConnection();
        if (connection != null) connection.disconnect(Component.translatable("disconnect.quitting"));
        mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (checkIn.isOpen()) {
            if (key == 256) checkIn.close();
            return true;
        }
        if (tab == Tab.PLAY && party.keyPressed(key, scanCode, modifiers)) return true;
        if (key == 256) {
            if (tab == Tab.SHOP && shop.isModalOpen()) {
                shop.closeModal();
                return true;
            }
            if (tab == Tab.PLAY && match.back()) return true;
            if (tab == Tab.CRAFT && craft != null && craft.back()) return true;
            if (tab == Tab.PLAY && browser.back()) {
                click();
                return true;
            }
            if (tab != Tab.PLAY) {
                switchTab(Tab.PLAY);
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (checkIn.isOpen()) return true;
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
