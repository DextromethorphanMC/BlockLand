package com.blockland.client.screen;

import com.blockland.client.ClientKitLayouts;
import com.blockland.client.font.UiFont;
import com.blockland.common.KitSlots;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.C2SRequestKit;
import com.blockland.network.packet.S2CKitData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * The arrangement is saved on this computer ({@link ClientKitLayouts}) and sent along when queueing.
 */
public final class KitLayoutScreen extends Screen {
    private static final int SLOT = 20;
    private static final int C_BG_TOP = 0xFF0A0E15;
    private static final int C_BG_BOTTOM = 0xFF151D2A;
    private static final int C_PANEL = 0xD0101722;
    private static final int C_SLOT = 0xFF1C2638;
    private static final int C_SLOT_HOVER = 0xFF2A3854;
    private static final int C_SLOT_LOCKED = 0xFF141B28;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_MUTED = 0xFF8D99AE;
    private static final int C_DIM = 0xFF5C6678;
    private static final int C_ACCENT = 0xFFF5A623;
    private static final int C_GO = 0xFF3FA34D;

    private final Screen parent;
    private static final String SAVE = "blockland.kit.save", RESET = "blockland.kit.reset", BACK = "blockland.kit.back";
    private final String modeId;
    private final String modeTitle;
    @Nullable
    private List<Entry> entries;
    private boolean customized;
    private String signature = "";
    private boolean dirty;
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
    }

    public String modeId() {
        return modeId;
    }

    @Override
    protected void init() {
        if (entries == null) BLNetwork.sendToServer(new C2SRequestKit(modeId));
    }

    /** Server sent the kit preset; the locally saved arrangement is applied if it was made for this preset. */
    public void setData(S2CKitData data) {
        signature = data.signature();
        List<String> defaults = data.entries().stream().map(S2CKitData.Entry::defaultSlot).toList();
        List<String> saved = ClientKitLayouts.get(modeId, signature)
                .map(ClientKitLayouts.Layout::slots)
                .filter(slots -> KitSlots.validateLayout(defaults, slots) == null)
                .orElse(null);
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < data.entries().size(); i++) {
            S2CKitData.Entry e = data.entries().get(i);
            list.add(new Entry(e.item(), e.defaultSlot(), saved != null ? saved.get(i) : e.defaultSlot()));
        }
        entries = list;
        customized = saved != null;
        dirty = false;
        held = -1;
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
        return 30 + 4 * SLOT + 8 + 40;
    }

    private void layout() {
        left = (width - panelW()) / 2 + 12;
        top = (height - panelH()) / 2 + 26;
    }

    /** Screen position of a slot; null for slots that are not shown. */
    @Nullable
    private int[] slotPos(String slot) {
        int gridX = left + 30;
        int armor = KitSlots.ARMOR.indexOf(slot);
        if (armor >= 0) return new int[]{left, top + armor * SLOT};
        if (KitSlots.OFFHAND.equals(slot)) return new int[]{left, top + 4 * SLOT + 8};
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
        return List.of(
                new Btn(gridX, y, 58, 18, SAVE, dirty ? C_GO : 0xFF3A4252),
                new Btn(gridX + 64, y, 58, 18, RESET, customized || dirty ? 0xFF4A5468 : 0xFF2A3040),
                new Btn(gridX + 128, y, 52, 18, BACK, 0xFF2A3446));
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
        int px = left - 12, py = top - 26;
        g.fill(px, py, px + panelW(), py + panelH(), C_PANEL);
        g.fill(px, py, px + 3, py + panelH(), C_ACCENT);
        String title = tr("blockland.kit.title");
        UiFont.draw(g, title, left, top - 18, C_TEXT, false);
        UiFont.draw(g, modeTitle, left + UiFont.width(title) + 8, top - 18, C_MUTED, false);
        String status = tr(entries == null ? "blockland.kit.status.loading" : dirty ? "blockland.kit.status.unsaved"
                : customized ? "blockland.kit.status.custom" : "blockland.kit.status.default");
        UiFont.draw(g, status, px + panelW() - 10 - UiFont.width(status), top - 18, dirty ? C_ACCENT : C_DIM, false);

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
        UiFont.draw(g, tr("blockland.kit.hint"), left, top + 4 * SLOT + 54, C_DIM, false);

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
        if (button == 1) {
            held = -1;
            return true;
        }
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        for (Btn b : buttons()) {
            if (!b.contains(mouseX, mouseY)) continue;
            switch (b.label) {
                case SAVE -> {
                    if (dirty && entries != null) {
                        ClientKitLayouts.save(modeId, signature, entries.stream().map(e -> e.slot).toList());
                        customized = true;
                        dirty = false;
                        click();
                    }
                }
                case RESET -> {
                    if (entries != null && (customized || dirty)) {
                        ClientKitLayouts.remove(modeId);
                        for (Entry e : entries) e.slot = e.defaultSlot;
                        customized = false;
                        dirty = false;
                        held = -1;
                        click();
                    }
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

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
