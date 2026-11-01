package com.blockland.client.backpack;

import com.blockland.BlockLand;
import com.blockland.anim.Spring;
import com.blockland.backpack.BackpackMenus;
import com.blockland.backpack.Backpacks;
import com.blockland.client.font.UiFont;
import com.blockland.client.render.RenderClock;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.ui.Draw;
import com.blockland.mixin.inventory.CreativeScreenAccess;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.CreativeModeTabRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blockland.client.backpack.InventoryLayout.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The creative inventory (UI spec §1), in place of vanilla's: vanilla's own creative screen underneath — its item picker,
 * tab contents, search, infinite stacks, middle-click clone, shift-click delete, Q, hotbar keys and saved hotbars are
 * unchanged — laid out and drawn by BlockLand like the survival screen. A sidebar of tabs on the left, as tall as the
 * panel (search and the player's inventory — 玩家背包 — pinned at the top, every other tab — all pages — listed with its icon and
 * name between: BlockLand's first, then vanilla's and other mods', scrolling with the wheel and a visible bar; on a
 * narrow screen icons only), the search field or "创造模式 · tab" in the header, the item grid with its own
 * scrollbar, the hotbar under it; the inventory tab is the survival layout with the destroy-item slot where crafting
 * would be, the backpack slot and its storage included.
 */
public final class BlockLandCreativeScreen extends CreativeModeInventoryScreen implements BackpackScreen {
    /** Sidebar widths: names beside the icons, or icons only on a narrow screen; the gap to the panel. */
    private static final int RAIL_WIDE = 118, RAIL_NARROW = 36, GAP = 6;
    /** A tab's row and a group heading's row in the sidebar's list. */
    private static final int ROW_H = 20, HEADING_H = 15;
    /** Rows of the item grid (vanilla's picker is patched to match: {@code mixin/inventory/ItemPickerMenuMixin}). */
    public static final int GRID_ROWS = 7;
    private static final int GRID_Y = 34;
    private static final int SCROLL_W = 6;
    private static final int SEARCH_W = 150;

    private final LocalPlayer player;
    private final SlotPainter painter = new SlotPainter();
    private final Spring panelHeight = new Spring(BASE_HEIGHT).config(260, 28);
    private final Spring buttonHover = new Spring(0).config(420, 30), brightHover = new Spring(0).config(420, 30);
    private final Spring scrollHover = new Spring(0).config(420, 30);
    private final Map<CreativeModeTab, Spring> tabHover = new HashMap<>();
    private final PanelDrag drag = new PanelDrag();
    private final Spring gripHover = new Spring(0).config(420, 30);
    private float railScroll;
    /** The sidebar's width this frame (wide or narrow by the screen's width). */
    private int rail = RAIL_WIDE;
    private boolean revealSelected = true;
    @Nullable
    private CreativeModeTab pressedTab;
    private boolean scrollDragging, draggingBrightness;
    private float xMouse, yMouse;
    @Nullable
    private Slot hovered;

    public BlockLandCreativeScreen(LocalPlayer player, FeatureFlagSet features, boolean operatorTab) {
        super(player, features, operatorTab);
        this.player = player;
        this.imageWidth = RAIL_WIDE + GAP + contentW();
        this.imageHeight = BASE_HEIGHT;
    }

    /** The item grid's scrollbar, right of its nine columns (they follow the cell size). */
    private static int scrollX() {
        return PAD + 9 * PITCH + 2;
    }

    /** The content's width, right of the sidebar. */
    private static int contentW() {
        return scrollX() + SCROLL_W + PAD;
    }

    /** The panel's size with cells of {@code cell} pixels: what the grip resizes (laid out for it as it goes). */
    private int[] sizeFor(int cell) {
        InventoryLayout.setCell(cell);
        return new int[]{rail + GAP + contentW(), inventoryTab() ? InventoryLayout.height(storageShown() ? rows() : 0) : BASE_HEIGHT};
    }

    /** Where the panel's content starts, right of the sidebar. */
    private int off() {
        return rail + GAP;
    }

    private CreativeScreenAccess access() {
        return (CreativeScreenAccess) (Object) this;
    }

    private static CreativeModeTab selected() {
        return CreativeScreenAccess.blockland$selectedTab();
    }

    private static boolean inventoryTab() {
        return selected().getType() == CreativeModeTab.Type.INVENTORY;
    }

    // ---- state -------------------------------------------------------------------------------------

    @Override
    public boolean backpackSlotShown() {
        return inventoryTab();
    }

    @Override
    public boolean storageShown() {
        return inventoryTab() && ModernInventoryScreen.rememberOpen && rows() > 0;
    }

    private int rows() {
        return Backpacks.capacity(Backpacks.worn(player)) / 9;
    }

    /** The player menu's backpack state follows what is shown (creative moves never reach the server's menu). */
    private void syncOpen() {
        if (player.inventoryMenu instanceof BackpackMenus.Host host) host.blockland$setBackpackOpen(storageShown());
    }

    @Override
    protected void init() {
        InventoryLayout.setCell(PanelDrag.cell());
        rail = width >= RAIL_WIDE + GAP + contentW() + 16 ? RAIL_WIDE : RAIL_NARROW;
        imageWidth = rail + GAP + contentW();
        imageHeight = Math.round(panelHeight.get());
        super.init();
        if (!player.hasInfiniteMaterials()) return;
        // Forge's page arrows: the rail shows every tab
        for (GuiEventListener child : List.copyOf(children())) {
            if (child instanceof AbstractButton) removeWidget(child);
        }
        layout();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        syncOpen();
    }

    /** A row of the sidebar's list: a tab, or a group heading ({@code tab} null); {@code y} within the list. */
    private record Row(@Nullable CreativeModeTab tab, String heading, int y, int h) {
    }

    /** The scrolling list: BlockLand's tabs under their heading, then vanilla's and other mods', each in Forge's order. */
    private static List<Row> tabRows() {
        List<CreativeModeTab> ours = new ArrayList<>(), others = new ArrayList<>();
        CreativeModeTab search = CreativeModeTabs.searchTab();
        for (CreativeModeTab tab : CreativeModeTabRegistry.getSortedCreativeModeTabs()) {
            if (!tab.shouldDisplay() || tab == search || tab.getType() == CreativeModeTab.Type.INVENTORY) continue;
            ResourceLocation id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            (id != null && id.getNamespace().equals(BlockLand.MODID) ? ours : others).add(tab);
        }
        // Forge's sorted list leaves out the tabs vanilla shows on every page: the saved hotbars and the operator
        // items (search and the player's inventory are pinned above the list)
        for (ResourceKey<CreativeModeTab> key : List.of(CreativeModeTabs.HOTBAR, CreativeModeTabs.OP_BLOCKS)) {
            CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(key);
            if (tab != null && tab.shouldDisplay() && !others.contains(tab)) others.add(tab);
        }
        List<Row> out = new ArrayList<>();
        int y = 0;
        for (int group = 0; group < 2; group++) {
            List<CreativeModeTab> tabs = group == 0 ? ours : others;
            if (tabs.isEmpty()) continue;
            out.add(new Row(null, tr(group == 0 ? "blockland.inventory.tabs_blockland" : "blockland.inventory.tabs_other"), y, HEADING_H));
            y += HEADING_H;
            for (CreativeModeTab tab : tabs) {
                out.add(new Row(tab, "", y, ROW_H));
                y += ROW_H;
            }
        }
        return out;
    }

    private static int listHeight(List<Row> rows) {
        return rows.isEmpty() ? 0 : rows.getLast().y() + rows.getLast().h();
    }

    /** Vanilla's player-inventory tab (not in Forge's sorted list, which leaves out the tabs shown on every page). */
    @Nullable
    private static CreativeModeTab inventoryTabOf() {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeModeTabs.INVENTORY);
    }

    /** The panel's size and place (eased), every slot's place, the search box under the drawn field. */
    private void layout() {
        InventoryLayout.setCell(PanelDrag.cell());
        imageWidth = rail + GAP + contentW();
        int target = inventoryTab() ? InventoryLayout.height(storageShown() ? rows() : 0) : BASE_HEIGHT;
        panelHeight.setTarget(target);
        imageHeight = Math.round(panelHeight.get());
        // moved by its header and sized by its grip, the sidebar along
        int[] at = PanelDrag.place(imageWidth, imageHeight, width, height);
        leftPos = at[0];
        topPos = at[1];
        Slot destroy = access().blockland$destroyItemSlot();
        if (inventoryTab()) {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                InventoryLayout.place(slot, slot == destroy ? new int[]{TRASH_X, TRASH_Y} : InventoryLayout.cell(i, false), off(), 0);
            }
        } else {
            for (int i = 0; i < menu.slots.size(); i++) {
                int[] cell = i < GRID_ROWS * 9 ? new int[]{PAD + (i % 9) * PITCH, GRID_Y + (i / 9) * PITCH}
                        : i < GRID_ROWS * 9 + 9 ? new int[]{PAD + (i - GRID_ROWS * 9) * PITCH, HOTBAR_Y} : null;
                InventoryLayout.place(menu.slots.get(i), cell, off(), 0);
            }
        }
        EditBox search = access().blockland$searchBox();
        if (search != null) {
            search.setX(leftPos + off() + PAD + 18);
            search.setY(topPos + 9);
            search.setWidth(SEARCH_W - 22);
        }
        if (revealSelected) {
            // the tab the screen opens on is in view
            revealSelected = false;
            float view = listBottom() - listTop();
            for (Row row : tabRows()) {
                if (row.tab() != selected()) continue;
                if (row.y() + row.h() - railScroll > view) railScroll = row.y() + row.h() - view;
                if (row.y() - HEADING_H < railScroll) railScroll = Math.max(0, row.y() - HEADING_H);
            }
        }
        railScroll = Math.max(0, Math.min(railScroll, railMax()));
    }

    private boolean revealed(Slot slot) {
        return slot.y + InventoryLayout.itemSize() + 2 <= imageHeight - 2;
    }

    private boolean canScrollGrid() {
        return !inventoryTab() && selected().canScroll() && menu.canScroll();
    }

    // ---- the tab sidebar ------------------------------------------------------------------------------

    private boolean wide() {
        return rail >= RAIL_WIDE;
    }

    /** The sidebar card: beside the panel and exactly as tall (the tabs that do not fit scroll with the wheel). */
    private int sideHeight() {
        return imageHeight;
    }

    private int sideTop() {
        return topPos;
    }

    /** Under the two pinned rows (search, the player's inventory). */
    private int listTop() {
        return sideTop() + 8 + 2 * ROW_H + 6;
    }

    private int listBottom() {
        return sideTop() + sideHeight() - 8;
    }

    private float railMax() {
        return Math.max(0, listHeight(tabRows()) - (listBottom() - listTop()));
    }

    private boolean inSidebar(double mouseX, double mouseY) {
        return mouseX >= leftPos && mouseX < leftPos + rail && mouseY >= sideTop() && mouseY < sideTop() + sideHeight();
    }

    /** The pinned rows at the top: search, then the player's inventory (equipment, crafting's place, the backpack). */
    private int[] pinnedCell(boolean search) {
        return new int[]{leftPos + 4, sideTop() + 8 + (search ? 0 : ROW_H)};
    }

    @Nullable
    private CreativeModeTab tabAt(double mouseX, double mouseY) {
        if (!inSidebar(mouseX, mouseY) || mouseX < leftPos + 4 || mouseX >= leftPos + rail - 4) return null;
        int[] top = pinnedCell(true), bottom = pinnedCell(false);
        if (mouseY >= top[1] && mouseY < top[1] + ROW_H) return CreativeModeTabs.searchTab();
        if (mouseY >= bottom[1] && mouseY < bottom[1] + ROW_H) return inventoryTabOf();
        if (mouseY < listTop() || mouseY >= listBottom()) return null;
        double at = mouseY - listTop() + railScroll;
        for (Row row : tabRows()) {
            if (row.tab() != null && at >= row.y() && at < row.y() + row.h()) return row.tab();
        }
        return null;
    }

    private void drawRail(GuiGraphics g, int mouseX, int mouseY) {
        InventoryStyle.window(g, leftPos, sideTop(), rail, sideHeight());
        CreativeModeTab over = tabAt(mouseX, mouseY);
        int[] pin = pinnedCell(true);
        drawTab(g, CreativeModeTabs.searchTab(), tr("blockland.inventory.search_tab"), pin[0], pin[1], over, false);
        CreativeModeTab inventory = inventoryTabOf();
        pin = pinnedCell(false);
        if (inventory != null) drawTab(g, inventory, tr("blockland.inventory.player_tab"), pin[0], pin[1], over, false);
        int line = InventoryStyle.fade(InventoryStyle.line(), 0.8f);
        g.fill(leftPos + 8, listTop() - 4, leftPos + rail - 8, listTop() - 3, line);
        List<Row> rows = tabRows();
        boolean scrolls = railMax() > 0;
        g.enableScissor(leftPos, listTop(), leftPos + rail, listBottom());
        for (Row row : rows) {
            int y = listTop() + row.y() - Math.round(railScroll);
            if (y + row.h() < listTop() || y > listBottom()) continue;
            if (row.tab() == null) {
                if (wide()) Draw.text(g, row.heading(), leftPos + 9, y + 5, 0.7f, InventoryStyle.dim(), false);
                else g.fill(leftPos + 10, y + HEADING_H / 2, leftPos + rail - 10, y + HEADING_H / 2 + 1, line);
            } else {
                drawTab(g, row.tab(), row.tab().getDisplayName().getString(), leftPos + 4, y, over, scrolls);
            }
        }
        g.disableScissor();
        if (scrolls) {
            int view = listBottom() - listTop();
            InventoryStyle.scrollbar(g, leftPos + rail - 6, listTop(), 3, view, railScroll / railMax(),
                    view / (float) listHeight(rows), true, 0);
        }
    }

    /** One tab's row: its icon, its name (wide sidebar), eased hover, the selected one filled with a marker. */
    private void drawTab(GuiGraphics g, CreativeModeTab tab, String name, int x, int y, @Nullable CreativeModeTab over,
                         boolean besideBar) {
        int w = rail - 8 - (besideBar ? 6 : 0);
        Spring spring = tabHover.computeIfAbsent(tab, t -> new Spring(0).config(420, 30));
        spring.setTarget(tab == over ? 1 : 0);
        float h = spring.get();
        boolean on = tab == selected();
        InventoryStyle.round(g, x, y + 1, w, ROW_H - 2, 4,
                on ? InventoryStyle.cellHover() : InventoryStyle.fade(InventoryStyle.cellHover(), 0.1f + 0.9f * h));
        if (on) InventoryStyle.round(g, x + 1, y + 5, 2, ROW_H - 10, 1, InventoryStyle.strong());
        int ix = wide() ? x + 5 : x + (w - 16) / 2;
        g.renderItem(tab.getIconItem(), ix, y + 2);
        if (wide()) {
            String text = Draw.fit(name, (w - 30) / 0.8f);
            Draw.text(g, text, x + 26, y + 7, 0.8f, on ? InventoryStyle.text() : InventoryStyle.blend(InventoryStyle.dim(), InventoryStyle.text(), h), false);
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        xMouse = mouseX;
        yMouse = mouseY;
        Slot under = net.minecraft.client.Minecraft.getInstance().screen == this ? SlotPainter.under(menu, mouseX, mouseY, leftPos, topPos) : null;
        hovered = under != null && revealed(under) ? under : null;
        // vanilla's container screen, without the creative screen's own tabs, scroller, effects and labels
        renderContents(g, mouseX, mouseY, partialTick);
        renderCarriedItem(g, mouseX, mouseY);
        renderSnapbackItem(g);
        drawEffects(g);
        SlotPainter.tooltip(g, hovered, menu.getCarried(), this::getTooltipFromContainerItem, mouseX, mouseY, width, height);
        // the wide sidebar names its tabs; the narrow one only on hover
        CreativeModeTab tab = wide() ? null : tabAt(mouseX, mouseY);
        if (tab != null && menu.getCarried().isEmpty()) {
            InventoryStyle.tooltip(g, List.of(tab.getDisplayName().getString()), mouseX, mouseY, width, height);
        }
        Slot destroy = access().blockland$destroyItemSlot();
        if (inventoryTab() && destroy != null && hovered == destroy && menu.getCarried().isEmpty()) {
            InventoryStyle.tooltip(g, List.of(tr("blockland.inventory.destroy")), mouseX, mouseY, width, height);
        }
    }

    /** The panel is laid out (and sized by the grip) before vanilla draws its background with {@link #renderBg}. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos + off(), y = topPos;
        InventoryStyle.window(g, x, topPos, contentW(), imageHeight);
        drawRail(g, mouseX, mouseY);
        drawHeader(g, mouseX, mouseY);
        if (inventoryTab()) {
            drawInventory(g, x, y, mouseX, mouseY);
        } else {
            drawItems(g, x, y, mouseX, mouseY);
        }
        drawCells(g);
        int right = leftPos + imageWidth, bottom = topPos + imageHeight;
        gripHover.setTarget(drag.resizing() || PanelDrag.overGrip(mouseX, mouseY, right, bottom) ? 1 : 0);
        PanelDrag.drawGrip(g, right, bottom, gripHover.get());
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY) {
        int x = leftPos + off(), y = topPos;
        if (selected().hasSearchBar()) {
            EditBox box = access().blockland$searchBox();
            InventoryStyle.round(g, x + PAD, y + 5, SEARCH_W, 16, 4, InventoryStyle.cell());
            InventoryStyle.glyph(g, InventoryStyle.Glyph.SEARCH, x + PAD + 2, y + 5, InventoryStyle.glyph());
            String value = box == null ? "" : box.getValue();
            float tx = x + PAD + 20, ty = y + 9;
            if (value.isEmpty()) {
                Draw.text(g, tr("blockland.inventory.search"), tx, ty, 0.85f, InventoryStyle.dim(), false);
            } else {
                Draw.text(g, Draw.fit(value, (SEARCH_W - 26) / 0.85f), tx, ty, 0.85f, InventoryStyle.text(), false);
            }
            if (box != null && box.isFocused() && (RenderClock.nanos() / 500_000_000L) % 2 == 0) {
                int cursor = Math.min(box.getCursorPosition(), value.length());
                float cx = tx + UiFont.width(value.substring(0, cursor)) * 0.85f;
                if (cx < x + PAD + SEARCH_W - 4) g.fill(Math.round(cx), y + 8, Math.round(cx) + 1, y + 18, InventoryStyle.text());
            }
        } else {
            // "创造模式 · tab": the creative screen says so (its inventory tab is laid out like the survival screen)
            String mode = tr("blockland.inventory.creative") + " · ";
            String name = inventoryTab() ? tr("blockland.inventory.player_tab") : selected().getDisplayName().getString();
            Draw.text(g, mode, x + PAD, y + 9, 1f, InventoryStyle.dim(), false);
            float nx = x + PAD + UiFont.width(mode);
            Draw.text(g, Draw.fit(name, x + contentW() - PAD - 120 - nx), nx, y + 9, 1f, InventoryStyle.text(), false);
        }
        int bx = leftPos + imageWidth - PAD - 54, by = y + 11;
        String label = tr("blockland.inventory.brightness");
        Draw.text(g, label, bx - 6 - UiFont.width(label) * 0.75f, by - 2, 0.75f, InventoryStyle.dim(), false);
        brightHover.setTarget(draggingBrightness || overBrightness(mouseX, mouseY) ? 1 : 0);
        InventoryStyle.brightnessBar(g, bx, by, 54, 3, brightHover.get());
    }

    /** An item tab: the grid with its scrollbar, the hotbar with how many items beside its label. */
    private void drawItems(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int trackY = y + GRID_Y, trackH = GRID_ROWS * PITCH - 2;
        int rows = Math.max(1, (menu.items.size() + 8) / 9);
        scrollHover.setTarget(scrollDragging || overScrollbar(mouseX, mouseY) ? 1 : 0);
        InventoryStyle.scrollbar(g, x + scrollX(), trackY, SCROLL_W, trackH, access().blockland$scrollOffs(),
                GRID_ROWS / (float) Math.max(GRID_ROWS, rows), canScrollGrid(), scrollHover.get());
        Draw.text(g, tr("blockland.inventory.hotbar"), x + PAD, y + HOTBAR_Y - 13, 0.8f, InventoryStyle.dim(), false);
        String count = tr("blockland.inventory.items", menu.items.size());
        Draw.text(g, count, x + PAD + 9 * PITCH - 2 - UiFont.width(count) * 0.75f, y + HOTBAR_Y - 12, 0.75f, InventoryStyle.dim(), false);
        g.fill(x + PAD, y + HOTBAR_Y - 4, x + PAD + 9 * PITCH - 2, y + HOTBAR_Y - 3, InventoryStyle.fade(InventoryStyle.line(), 0.7f));
    }

    /** The inventory tab: the survival layout, the destroy-item slot instead of crafting. */
    private void drawInventory(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        InventoryStyle.card(g, x + CHARACTER_X, y + EQUIP_Y, CHARACTER_W, CHARACTER_H);
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + CHARACTER_X + 2, y + EQUIP_Y + 4, x + CHARACTER_X + CHARACTER_W - 2,
                y + EQUIP_Y + CHARACTER_H - 4, Math.round(30 * CELL / (float) DEFAULT_CELL), 0.0625f, xMouse, yMouse, player);
        InventoryStyle.card(g, x + CARD_X, y + EQUIP_Y, CARD_W, CHARACTER_H);
        Draw.text(g, tr("blockland.inventory.destroy"), x + CARD_X + 5, y + EQUIP_Y + 5, 0.75f, InventoryStyle.dim(), false);
        String hint = tr("blockland.inventory.destroy_hint");
        Draw.text(g, Draw.fit(hint, (CARD_W - 10) / 0.7f), x + CARD_X + 5, y + TRASH_Y + CELL + 8, 0.7f, InventoryStyle.dim(), false);
        Draw.text(g, tr("blockland.inventory.title"), x + PAD, y + MAIN_LABEL_Y, 0.8f, InventoryStyle.dim(), false);
        g.fill(x + PAD, y + HOTBAR_Y - 4, x + WIDTH - PAD, y + HOTBAR_Y - 3, InventoryStyle.fade(InventoryStyle.line(), 0.7f));
        if (!Backpacks.worn(player).isEmpty()) {
            boolean over = ModernInventoryScreen.inside(mouseX, mouseY, x + BUTTON_X, y + BUTTON_Y, BUTTON_W, BUTTON_H);
            buttonHover.setTarget(over ? 1 : 0);
            InventoryStyle.button(g, x + BUTTON_X, y + BUTTON_Y, BUTTON_W, BUTTON_H,
                    tr(storageShown() ? "blockland.inventory.backpack_close" : "blockland.inventory.backpack_open"), buttonHover.get(), storageShown());
        }
        if (imageHeight > BASE_HEIGHT + 4) {
            g.fill(x + PAD, y + BASE_HEIGHT - 2, x + WIDTH - PAD, y + BASE_HEIGHT - 1, InventoryStyle.line());
            ItemStack worn = Backpacks.worn(player);
            if (!worn.isEmpty() && imageHeight > STORAGE_LABEL_Y + 10) {
                UiFont.draw(g, InventoryStyle.readable(net.minecraft.locale.Language.getInstance().getVisualOrder(worn.getHoverName())),
                        x + PAD, y + STORAGE_LABEL_Y, 0.8f, InventoryStyle.dim(), false);
            }
        }
    }

    /** The cell under every slot shown, eased hover, glyphs in empty equipment slots and the destroy-item slot. */
    private void drawCells(GuiGraphics g) {
        Slot destroy = access().blockland$destroyItemSlot();
        boolean inventory = inventoryTab();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive() || slot.x < 0 || !revealed(slot)) continue;
            int cx = leftPos + slot.x - 2, cy = topPos + slot.y - 2;
            InventoryStyle.cell(g, cx, cy, CELL, painter.hover(slot, slot == hovered));
            InventoryStyle.Glyph glyph = inventory && slot == destroy ? InventoryStyle.Glyph.TRASH
                    : inventory ? InventoryLayout.glyph(i) : null;
            if (glyph != null && !slot.hasItem()) InventoryStyle.glyph(g, glyph, cx + (CELL - 16) / 2, cy + (CELL - 16) / 2, InventoryStyle.glyph());
        }
    }

    private void drawEffects(GuiGraphics g) {
        if (player.getActiveEffects().isEmpty()) return;
        int w = 124;
        int x = leftPos + imageWidth + 8;
        if (x + w > width - 4) x = leftPos - 8 - w;
        if (x < 4) return;
        InventoryStyle.effects(g, player.getActiveEffects(), x, topPos, w);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (!revealed(slot)) return;
        SlotPainter.slot(g, this, menu, slot, isQuickCrafting, quickCraftSlots, imageWidth);
    }

    @Override
    protected void renderSlotHighlightBack(GuiGraphics g) {
    }

    @Override
    protected void renderSlotHighlightFront(GuiGraphics g) {
    }

    @Override
    public void renderCarriedItem(GuiGraphics g, int mouseX, int mouseY) {
        SlotPainter.carried(g, this, menu, mouseX, mouseY, isQuickCrafting, quickCraftSlots);
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
    }

    // vanilla's tabs and scroller are not where it would look for them: the rail and BlockLand's scrollbar take the clicks

    @Override
    protected boolean checkTabClicked(CreativeModeTab tab, double mouseX, double mouseY) {
        return false;
    }

    @Override
    protected boolean checkTabHovering(GuiGraphics g, CreativeModeTab tab, int mouseX, int mouseY) {
        return false;
    }

    @Override
    protected void renderTabButton(GuiGraphics g, CreativeModeTab tab) {
    }

    @Override
    protected boolean insideScrollbar(double mouseX, double mouseY) {
        return false;
    }

    /** The sidebar reaches above and below the panel: a click there is not outside (it would throw the carried stack). */
    /** Vanilla asks about a slot's 16 × 16 item; the items fill their (resizable) cells. */
    @Override
    protected boolean isHovering(int x, int y, int w, int h, double mouseX, double mouseY) {
        if (w == 16 && h == 16) w = h = InventoryLayout.itemSize();
        return super.isHovering(x, y, w, h, mouseX, mouseY);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        if (inSidebar(mouseX, mouseY)) return super.hasClickedOutside(left, top, left, top, button);
        return super.hasClickedOutside(mouseX, mouseY, left, top, button);
    }

    // ---- input -----------------------------------------------------------------------------------------

    private boolean overScrollbar(double mouseX, double mouseY) {
        return !inventoryTab() && ModernInventoryScreen.inside(mouseX, mouseY, leftPos + off() + scrollX() - 2, topPos + GRID_Y,
                SCROLL_W + 4, GRID_ROWS * PITCH - 2);
    }

    private boolean overBrightness(double mouseX, double mouseY) {
        return ModernInventoryScreen.inside(mouseX, mouseY, leftPos + imageWidth - PAD - 56, topPos + 6, 58, 12);
    }

    /** The content's header outside the search field (the brightness bar is tested first): held there, the panel is moved. */
    private boolean overHeader(double mouseX, double mouseY) {
        int x = leftPos + off();
        if (!ModernInventoryScreen.inside(mouseX, mouseY, x, topPos, contentW(), HEADER)) return false;
        return !selected().hasSearchBar() || !ModernInventoryScreen.inside(mouseX, mouseY, x + PAD, topPos + 5, SEARCH_W, 16);
    }

    private void scrollTo(double mouseY) {
        int trackY = topPos + GRID_Y, trackH = GRID_ROWS * PITCH - 2;
        int rows = Math.max(1, (menu.items.size() + 8) / 9);
        int thumb = Math.max(14, Math.round(trackH * Math.max(0.08f, GRID_ROWS / (float) Math.max(GRID_ROWS, rows))));
        float offs = (float) ((mouseY - trackY - thumb / 2.0) / Math.max(1, trackH - thumb));
        offs = Math.max(0, Math.min(1, offs));
        access().blockland$setScrollOffs(offs);
        menu.scrollTo(offs);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && PanelDrag.overGrip(mouseX, mouseY, leftPos + imageWidth, topPos + imageHeight)) {
            drag.pressGrip(mouseX, mouseY, leftPos, topPos, width, height, this::sizeFor);
            return true;
        }
        if (button == 0) {
            CreativeModeTab tab = tabAt(mouseX, mouseY);
            if (tab != null) {
                pressedTab = tab;
                return true;
            }
            if (inSidebar(mouseX, mouseY)) return true;
            if (canScrollGrid() && overScrollbar(mouseX, mouseY)) {
                scrollDragging = true;
                scrollTo(mouseY);
                return true;
            }
            if (inventoryTab() && !Backpacks.worn(player).isEmpty() && ModernInventoryScreen.inside(mouseX, mouseY,
                    leftPos + off() + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H)) {
                ModernInventoryScreen.rememberOpen = !storageShown();
                syncOpen();
                return true;
            }
            if (overBrightness(mouseX, mouseY)) {
                draggingBrightness = true;
                setBrightness(mouseX);
                return true;
            }
            if (overHeader(mouseX, mouseY)) {
                drag.press(mouseX, mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag.active()) {
            drag.release();
            return true;
        }
        if (pressedTab != null) {
            CreativeModeTab tab = tabAt(mouseX, mouseY);
            if (button == 0 && tab == pressedTab && tab != selected()) {
                access().blockland$selectTab(tab);
                layout();
            }
            pressedTab = null;
            return true;
        }
        if (scrollDragging) {
            scrollDragging = false;
            return true;
        }
        if (draggingBrightness) {
            draggingBrightness = false;
            ClientSettings.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (drag.active()) {
            drag.drag(mouseX, mouseY);
            return true;
        }
        if (scrollDragging) {
            scrollTo(mouseY);
            return true;
        }
        if (draggingBrightness) {
            setBrightness(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inSidebar(mouseX, mouseY)) {
            railScroll = Math.max(0, Math.min(railMax(), railScroll - (float) scrollY * ROW_H * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void setBrightness(double mouseX) {
        InventoryStyle.setBrightness((float) ((mouseX - (leftPos + imageWidth - PAD - 54)) / 53.0));
    }
}
