package com.blockland.client.screen;

import com.blockland.anim.Ticker;
import com.blockland.client.ClientKitLayouts;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.common.KitSlots;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.C2SRequestKit;
import com.blockland.network.packet.S2CKitData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * Loadout editor ("装备布局"): the player arranges a mode's starting kit in an inventory-shaped grid.
 * Works for any mode with a kit; armour stays in its slots, everything else can be moved or swapped.
 * A mode can have several named presets (tabs above the grid); the selected one is the one in use, sent along when
 * queueing. Presets are saved on this computer ({@link ClientKitLayouts}); switching tabs keeps unsaved edits in the
 * preset they were made in.
 */
public final class KitLayoutScreen extends Screen {
    private static final int SLOT = 20;
    private static final int TAB_H = 14;
    private static final int C_BG_TOP = 0xFF0A0E15;
    private static final int C_BG_BOTTOM = 0xFF151D2A;
    private static final int C_PANEL = 0xD0101722;
    private static final int C_SLOT = 0xFF1C2638;
    private static final int C_SLOT_HOVER = 0xFF2A3854;
    private static final int C_SLOT_LOCKED = 0xFF141B28;
    private static final int C_TAB = 0xFF1C2638;
    private static final int C_TAB_HOVER = 0xFF2A3854;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_MUTED = 0xFF8D99AE;
    private static final int C_DIM = 0xFF5C6678;
    private static final int C_ACCENT = 0xFFF5A623;
    private static final int C_GO = 0xFF3FA34D;
    private static final int C_DANGER = 0xFFC0453A;

    private final Screen parent;
    private static final String SAVE = "blockland.kit.save", RESET = "blockland.kit.reset", BACK = "blockland.kit.back",
            DUPLICATE = "blockland.kit.preset.duplicate", RENAME = "blockland.kit.preset.rename",
            DELETE = "blockland.kit.preset.delete", CONFIRM_DELETE = "blockland.kit.preset.confirm_delete";
    private final String modeId;
    private final String modeTitle;
    private final EditBox nameField;
    @Nullable
    private S2CKitData kit;
    @Nullable
    private List<Entry> entries;
    /** The preset on screen, which is also the one in use. */
    private int preset;
    private boolean customized;
    /** The preset's arrangement was made for an older kit: the default is shown instead. */
    private boolean outdated;
    private String signature = "";
    private boolean dirty;
    private boolean renaming;
    private boolean confirmDelete;
    private int held = -1;
    private int left, top;

    private static final class Entry {
        /** A stand-in stack for drawing; the item's real data stays on the server (the lobby has no registries). */
        final ItemStack stack;
        final List<Component> tooltip;
        final String defaultSlot;
        String slot;

        Entry(S2CKitData.ItemView item, String defaultSlot, String slot) {
            this.stack = item.toStack();
            this.tooltip = item.tooltip();
            this.defaultSlot = defaultSlot;
            this.slot = slot;
        }

        boolean locked() {
            return KitSlots.isArmor(defaultSlot);
        }
    }

    public KitLayoutScreen(Screen parent, String modeId, String modeTitle) {
        super(Component.translatable("blockland.kit.title"));
        this.parent = parent;
        this.modeId = modeId;
        this.modeTitle = modeTitle;
        this.nameField = new EditBox(Minecraft.getInstance().font, 0, 0, 100, TAB_H,
                Component.translatable("blockland.kit.preset.name_hint"));
        nameField.setMaxLength(ClientKitLayouts.MAX_NAME);
    }

    public String modeId() {
        return modeId;
    }

    @Override
    protected void init() {
        if (entries == null) BLNetwork.sendToServer(new C2SRequestKit(modeId));
    }

    /** Server sent the kit preset; the preset in use is applied if it was made for this kit. */
    public void setData(S2CKitData data) {
        kit = data;
        signature = data.signature();
        preset = ClientKitLayouts.active(modeId);
        applyPreset();
    }

    /** Shows the selected preset: its own arrangement when it fits the server's kit, otherwise the default. */
    private void applyPreset() {
        if (kit == null) return;
        ClientKitLayouts.Preset chosen = ClientKitLayouts.presets(modeId).get(preset);
        List<String> defaults = kit.entries().stream().map(S2CKitData.Entry::defaultSlot).toList();
        boolean fits = !chosen.isDefault() && chosen.signature().equals(signature)
                && KitSlots.validateLayout(defaults, chosen.slots()) == null;
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < kit.entries().size(); i++) {
            S2CKitData.Entry e = kit.entries().get(i);
            list.add(new Entry(e.item(), e.defaultSlot(), fits ? chosen.slots().get(i) : e.defaultSlot()));
        }
        entries = list;
        customized = fits;
        outdated = !chosen.isDefault() && !fits;
        dirty = false;
        held = -1;
        confirmDelete = false;
    }

    private List<String> currentSlots() {
        return entries == null ? List.of() : entries.stream().map(e -> e.slot).toList();
    }

    /** Keeps the edits of the preset on screen before another one is shown or created. */
    private void keepEdits() {
        if (!dirty || entries == null) return;
        ClientKitLayouts.save(modeId, preset, signature, currentSlots());
        dirty = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ---- layout ------------------------------------------------------------------------------

    private int panelW() {
        return 30 + 9 * SLOT + 24;
    }

    private int panelH() {
        return 48 + 4 * SLOT + 8 + 62;
    }

    private void layout() {
        left = (width - panelW()) / 2 + 12;
        top = (height - panelH()) / 2 + 48;
    }

    /** Screen position of a slot; null for slots that are not shown. */
    @Nullable
    private int[] slotPos(String slot) {
        int gridX = left + 30;
        int armor = KitSlots.ARMOR.indexOf(slot);
        if (armor >= 0) return new int[]{left, top + armor * SLOT};
        if (KitSlots.OFFHAND.equals(slot)) return new int[]{left, top + 4 * SLOT + 8};
        if (KitSlots.BACKPACK.equals(slot)) return new int[]{left, top + 5 * SLOT + 12};
        int index = KitSlots.mainIndex(slot);
        if (index < 0) return null;
        if (index < 9) return new int[]{gridX + index * SLOT, top + 3 * SLOT + 8};
        int row = (index - 9) / 9;
        int col = (index - 9) % 9;
        return new int[]{gridX + col * SLOT, top + row * SLOT};
    }

    private List<String> allSlots() {
        List<String> slots = new ArrayList<>(KitSlots.ARMOR);
        slots.add(KitSlots.OFFHAND);
        slots.add(KitSlots.BACKPACK);
        for (int i = 0; i < KitSlots.MAIN_SLOTS; i++) slots.add(String.valueOf(i));
        return slots;
    }

    @Nullable
    private String slotAt(double mx, double my) {
        for (String slot : allSlots()) {
            int[] p = slotPos(slot);
            if (p != null && mx >= p[0] && mx < p[0] + SLOT - 2 && my >= p[1] && my < p[1] + SLOT - 2) return slot;
        }
        return null;
    }

    private int entryAt(String slot) {
        if (entries == null) return -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).slot.equals(slot)) return i;
        return -1;
    }

    // ---- preset tabs -------------------------------------------------------------------------

    private enum TabKind { PREVIOUS, NAME, NEXT, NEW }

    /** One part of the preset selector: ‹ the preset in use (n/m) › + 新建. */
    private record Tab(int x, int y, int w, TabKind kind, String label) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + TAB_H;
        }
    }

    private List<Tab> tabs() {
        List<ClientKitLayouts.Preset> presets = ClientKitLayouts.presets(modeId);
        int y = top - 22, right = left + panelW() - 24;
        String add = tr("blockland.kit.preset.new");
        int addW = UiFont.width(add) + 10;
        int nameW = right - left - 2 * (TAB_H + 3) - addW - 3;
        String count = " (" + (preset + 1) + "/" + presets.size() + ")";
        String name = Draw.fit(presets.get(Math.min(preset, presets.size() - 1)).name(), nameW - 12 - UiFont.width(count)) + count;
        List<Tab> tabs = new ArrayList<>();
        tabs.add(new Tab(left, y, TAB_H, TabKind.PREVIOUS, "‹"));
        tabs.add(new Tab(left + TAB_H + 3, y, nameW, TabKind.NAME, name));
        tabs.add(new Tab(left + TAB_H + 3 + nameW + 3, y, TAB_H, TabKind.NEXT, "›"));
        if (presets.size() < ClientKitLayouts.MAX_PRESETS) tabs.add(new Tab(right - addW, y, addW, TabKind.NEW, add));
        return tabs;
    }

    // ---- buttons -----------------------------------------------------------------------------

    /** @param label translation key, also the button's identity */
    private record Btn(int x, int y, int w, int h, String label, int color) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private List<Btn> buttons() {
        int y = top + 4 * SLOT + 30;
        int gridX = left + 30;
        boolean room = ClientKitLayouts.presets(modeId).size() < ClientKitLayouts.MAX_PRESETS;
        return List.of(
                new Btn(gridX, y, 58, 18, SAVE, dirty ? C_GO : 0xFF3A4252),
                new Btn(gridX + 64, y, 58, 18, RESET, customized || dirty || outdated ? 0xFF4A5468 : 0xFF2A3040),
                new Btn(gridX + 128, y, 52, 18, BACK, 0xFF2A3446),
                new Btn(gridX, y + 22, 58, 18, DUPLICATE, room ? 0xFF2A3446 : 0xFF20262F),
                new Btn(gridX + 64, y + 22, 58, 18, RENAME, renaming ? C_ACCENT : 0xFF2A3446),
                new Btn(gridX + 128, y + 22, 52, 18, confirmDelete ? CONFIRM_DELETE : DELETE, confirmDelete ? C_DANGER : 0xFF3A2A30));
    }

    // ---- rendering ---------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, C_BG_TOP, C_BG_BOTTOM);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        super.render(g, mouseX, mouseY, partialTick);
        int px = left - 12, py = top - 48;
        g.fill(px, py, px + panelW(), py + panelH(), C_PANEL);
        g.fill(px, py, px + 3, py + panelH(), C_ACCENT);
        String title = tr("blockland.kit.title");
        UiFont.draw(g, title, left, top - 40, C_TEXT, false);
        UiFont.draw(g, modeTitle, left + UiFont.width(title) + 8, top - 40, C_MUTED, false);
        String status = tr(entries == null ? "blockland.kit.status.loading" : dirty ? "blockland.kit.status.unsaved"
                : outdated ? "blockland.kit.status.outdated" : customized ? "blockland.kit.status.custom" : "blockland.kit.status.default");
        UiFont.draw(g, status, px + panelW() - 10 - UiFont.width(status), top - 40, dirty || outdated ? C_ACCENT : C_DIM, false);

        renderTabs(g, mouseX, mouseY);

        String hovered = slotAt(mouseX, mouseY);
        for (String slot : allSlots()) {
            int[] p = slotPos(slot);
            if (p == null) continue;
            boolean armor = KitSlots.isArmor(slot);
            int color = armor ? C_SLOT_LOCKED : slot.equals(hovered) ? C_SLOT_HOVER : C_SLOT;
            g.fill(p[0], p[1], p[0] + SLOT - 2, p[1] + SLOT - 2, color);
        }
        String hotbar = tr("blockland.kit.hotbar");
        UiFont.draw(g, hotbar, left + 30 + 9 * SLOT + 4 - UiFont.width(hotbar) - 2, top + 3 * SLOT + 8 - 9, C_DIM, false);

        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                int[] p = slotPos(entry.slot);
                if (p == null) continue;
                if (i == held) {
                    g.fill(p[0], p[1], p[0] + SLOT - 2, p[1] + SLOT - 2, 0x40F5A623);
                    continue;
                }
                g.renderItem(entry.stack, p[0] + 1, p[1] + 1);
                g.renderItemDecorations(font, entry.stack, p[0] + 1, p[1] + 1);
            }
        }

        for (Btn b : buttons()) {
            g.fill(b.x, b.y, b.x + b.w, b.y + b.h, b.contains(mouseX, mouseY) ? brighten(b.color) : b.color);
            UiFont.drawCentered(g, tr(b.label), b.x + b.w / 2, b.y + 5, C_TEXT, true);
        }
        UiFont.draw(g, tr("blockland.kit.hint"), left, top + 4 * SLOT + 76, C_DIM, false);
        UiFont.draw(g, tr("blockland.kit.preset.hint"), left, top + 4 * SLOT + 88, C_DIM, false);

        if (entries != null && held >= 0) {
            ItemStack stack = entries.get(held).stack;
            g.renderItem(stack, mouseX - 8, mouseY - 8);
            g.renderItemDecorations(font, stack, mouseX - 8, mouseY - 8);
        } else if (hovered != null) {
            int index = entryAt(hovered);
            if (index >= 0 && !entries.get(index).tooltip.isEmpty()) {
                g.setTooltipForNextFrame(font, entries.get(index).tooltip, java.util.Optional.empty(), mouseX, mouseY);
            }
        }
    }

    /** The preset selector: the preset in use in the accent colour (its name field while renaming), arrows, + 新建. */
    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        int count = ClientKitLayouts.presets(modeId).size();
        for (Tab tab : tabs()) {
            boolean selected = tab.kind == TabKind.NAME;
            if (selected && renaming) {
                int w = tab.w;
                Draw.rect(g, tab.x, tab.y, w, TAB_H, 0xFF05080D);
                Draw.outline(g, tab.x, tab.y, w, TAB_H, C_ACCENT);
                String value = nameField.getValue();
                if (value.isEmpty()) Draw.text(g, tr("blockland.kit.preset.name_hint"), tab.x + 5, tab.y + 3, 1f, C_DIM, false);
                else Draw.text(g, Draw.fit(value, w - 10), tab.x + 5, tab.y + 3, 1f, C_TEXT, false);
                if ((long) (Ticker.global().time() * 2) % 2 == 0) {
                    int caret = tab.x + 5 + Math.min(UiFont.width(value), w - 10);
                    g.fill(caret, tab.y + 2, caret + 1, tab.y + TAB_H - 2, C_ACCENT);
                }
                continue;
            }
            boolean enabled = tab.kind == TabKind.NAME || tab.kind == TabKind.NEW || count > 1;
            int color = selected ? C_ACCENT : enabled && tab.contains(mouseX, mouseY) ? C_TAB_HOVER : C_TAB;
            g.fill(tab.x, tab.y, tab.x + tab.w, tab.y + TAB_H, color);
            int text = selected ? 0xFF10141C : !enabled ? C_DIM : tab.kind == TabKind.NEW ? C_MUTED : C_TEXT;
            UiFont.drawCentered(g, tab.label, tab.x + tab.w / 2f, tab.y + 3, text, false);
        }
    }

    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 20);
        int gr = Math.min(255, ((color >> 8) & 0xFF) + 20);
        int b = Math.min(255, (color & 0xFF) + 20);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    // ---- input -------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();
        if (renaming) finishRename(true);
        if (button == 1) {
            held = -1;
            return true;
        }
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        for (Tab tab : tabs()) {
            if (!tab.contains(mouseX, mouseY) || entries == null) continue;
            int count = ClientKitLayouts.presets(modeId).size();
            switch (tab.kind) {
                case NAME -> {
                    return true;
                }
                case NEW -> {
                    keepEdits();
                    int added = ClientKitLayouts.add(modeId, "", signature, List.of());
                    if (added < 0) return true;
                    preset = added;
                }
                case PREVIOUS, NEXT -> {
                    if (count < 2) return true;
                    keepEdits();
                    preset = Math.floorMod(preset + (tab.kind == TabKind.NEXT ? 1 : -1), count);
                    ClientKitLayouts.setActive(modeId, preset);
                }
            }
            applyPreset();
            click();
            return true;
        }

        for (Btn b : buttons()) {
            if (!b.contains(mouseX, mouseY)) continue;
            if (!b.label.equals(DELETE) && !b.label.equals(CONFIRM_DELETE)) confirmDelete = false;
            switch (b.label) {
                case SAVE -> {
                    if (dirty && entries != null) {
                        ClientKitLayouts.save(modeId, preset, signature, currentSlots());
                        customized = true;
                        outdated = false;
                        dirty = false;
                        click();
                    }
                }
                case RESET -> {
                    if (entries != null && (customized || dirty || outdated)) {
                        ClientKitLayouts.save(modeId, preset, signature, List.of());
                        for (Entry e : entries) e.slot = e.defaultSlot;
                        customized = false;
                        outdated = false;
                        dirty = false;
                        held = -1;
                        click();
                    }
                }
                case DUPLICATE -> {
                    if (entries != null) {
                        keepEdits();
                        ClientKitLayouts.Preset current = ClientKitLayouts.presets(modeId).get(preset);
                        String name = tr("blockland.kit.preset.copy_suffix", current.name());
                        int added = ClientKitLayouts.add(modeId, name, signature, customized ? currentSlots() : List.of());
                        if (added >= 0) {
                            preset = added;
                            applyPreset();
                            click();
                        }
                    }
                }
                case RENAME -> {
                    renaming = true;
                    nameField.setFocused(true);
                    nameField.setValue(ClientKitLayouts.presets(modeId).get(preset).name());
                    nameField.moveCursorToEnd(false);
                    click();
                }
                case DELETE -> {
                    confirmDelete = true;
                    click();
                }
                case CONFIRM_DELETE -> {
                    ClientKitLayouts.remove(modeId, preset);
                    preset = ClientKitLayouts.active(modeId);
                    applyPreset();
                    click();
                }
                default -> {
                    click();
                    onClose();
                }
            }
            return true;
        }

        String slot = slotAt(mouseX, mouseY);
        if (slot == null || entries == null) return super.mouseClicked(mouseX, mouseY, button);
        confirmDelete = false;
        int target = entryAt(slot);
        if (held < 0) {
            if (target >= 0 && !entries.get(target).locked()) {
                held = target;
                click();
            }
            return true;
        }
        if (KitSlots.isArmor(slot) || (target >= 0 && entries.get(target).locked())) return true;
        Entry moving = entries.get(held);
        if (target >= 0 && target != held) entries.get(target).slot = moving.slot;
        if (!moving.slot.equals(slot)) dirty = true;
        moving.slot = slot;
        held = -1;
        click();
        return true;
    }

    private void finishRename(boolean apply) {
        if (apply) ClientKitLayouts.rename(modeId, preset, nameField.getValue());
        renaming = false;
        nameField.setFocused(false);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (renaming) {
            if (key == 256) { // escape: keep the old name
                finishRename(false);
                return true;
            }
            if (key == 257 || key == 335) { // enter
                finishRename(true);
                click();
                return true;
            }
            nameField.keyPressed(key, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (renaming) return nameField.charTyped(c, modifiers);
        return super.charTyped(c, modifiers);
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
