package com.blockland.client.backpack;

import com.blockland.anim.Spring;
import com.blockland.backpack.BackpackMenus;
import com.blockland.backpack.Backpacks;
import com.blockland.client.font.UiFont;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.ui.Draw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;

import javax.annotation.Nullable;
import java.util.List;

import static com.blockland.client.backpack.InventoryLayout.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The survival inventory (UI spec §1), in place of vanilla's: the player's own menu (every click, drag, number key,
 * double-click and shift-click is vanilla's, checked by the server) laid out and drawn by BlockLand — the equipment round
 * the character, crafting in its own card, the inventory and the hotbar as one clean grid, the worn backpack's storage
 * sliding open under it, the recipe book ({@link RecipeBookPanel}: vanilla's, drawn by BlockLand) beside it. Greys only;
 * the brightness bar in the header is the one setting.
 */
public final class ModernInventoryScreen extends AbstractContainerScreen<InventoryMenu> implements BackpackScreen, RecipeUpdateListener {
    /** Whether the storage was open when the screen was last closed (it opens that way again). */
    static boolean rememberOpen;

    private final Player player;
    private final SlotPainter painter = new SlotPainter();
    /** The panel's height, eased as the storage opens and closes. */
    private final Spring panelHeight = new Spring(BASE_HEIGHT).config(260, 28);
    private final Spring buttonHover = new Spring(0).config(420, 30), brightHover = new Spring(0).config(420, 30);
    private final Spring bookHover = new Spring(0).config(420, 30);
    private final RecipeBookPanel recipes;
    private final PanelDrag drag = new PanelDrag();
    private boolean expanded;
    private boolean draggingBrightness;
    private float xMouse, yMouse;
    @Nullable
    private Slot hovered;

    public ModernInventoryScreen(Player player) {
        super(player.inventoryMenu, player.getInventory(), Component.translatable("blockland.inventory.title"));
        this.player = player;
        this.expanded = rememberOpen;
        this.imageWidth = WIDTH;
        this.imageHeight = InventoryLayout.height(storageShown() ? rows() : 0);
        this.panelHeight.set(imageHeight);
        this.recipes = new RecipeBookPanel(menu);
    }

    // ---- state -------------------------------------------------------------------------------------

    @Override
    public boolean backpackSlotShown() {
        return true;
    }

    @Override
    public boolean storageShown() {
        return expanded && rows() > 0;
    }

    private int rows() {
        return Backpacks.capacity(Backpacks.worn(player)) / 9;
    }

    private void setExpanded(boolean open) {
        expanded = open;
        rememberOpen = open;
        syncOpen();
    }

    /** Tells the menu (here and on the server) whether a shift-click goes into the backpack. */
    private void syncOpen() {
        boolean shown = storageShown();
        if (menu instanceof BackpackMenus.Host host) host.blockland$setBackpackOpen(shown);
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, shown ? BackpackMenus.BUTTON_EXPAND : BackpackMenus.BUTTON_COLLAPSE);
        }
    }

    private boolean creative() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasInfiniteMaterials();
    }

    private void toCreative() {
        minecraft.setScreen(new CreativeModeInventoryScreen(minecraft.player, minecraft.player.connection.enabledFeatures(),
                minecraft.options.operatorItemsTab().get()));
    }

    @Override
    protected void init() {
        if (creative()) {
            toCreative();
            return;
        }
        super.init();
        // too narrow for the book beside the inventory: it opens over it (vanilla's rule)
        recipes.init(minecraft, width < RecipeBookPanel.W + RecipeBookPanel.GAP + imageWidth + 16);
        layout();
        syncOpen();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (creative()) {
            toCreative();
            return;
        }
        // the backpack was taken off (or swapped for another): the menu follows
        if (menu instanceof BackpackMenus.Host host && host.blockland$backpackOpen() != storageShown()) syncOpen();
        recipes.tick();
    }

    /** The panel's size and place (eased), and every slot's place in it. */
    private void layout() {
        panelHeight.setTarget(InventoryLayout.height(storageShown() ? rows() : 0));
        imageHeight = Math.round(panelHeight.get());
        // the open book beside the inventory: the two centred together (the inventory moves right, as vanilla's)
        boolean beside = recipes.visible() && !recipes.narrow();
        int book = beside ? RecipeBookPanel.W + RecipeBookPanel.GAP : 0;
        int group = (width - imageWidth - book) / 2, top = (height - imageHeight) / 2;
        // moved by its header: the book beside it goes along
        int[] shift = PanelDrag.apply(group, top, imageWidth + book, beside ? Math.max(imageHeight, RecipeBookPanel.H) : imageHeight, width, height);
        group += shift[0];
        leftPos = group + book;
        topPos = top + shift[1];
        recipes.place(beside ? group : (width - RecipeBookPanel.W) / 2, beside ? topPos : (height - RecipeBookPanel.H) / 2);
        for (int i = 0; i < menu.slots.size(); i++) InventoryLayout.place(menu.slots.get(i), InventoryLayout.cell(i, true), 0, 0);
    }

    /** The book open over the inventory (a narrow screen): the inventory is neither drawn nor touched. */
    private boolean covered() {
        return recipes.visible() && recipes.narrow();
    }

    /** Whether a slot is inside the panel as far as it has opened. */
    private boolean revealed(Slot slot) {
        return slot.y + 18 <= imageHeight - 2;
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        xMouse = mouseX;
        yMouse = mouseY;
        Slot under = covered() || recipes.contains(mouseX, mouseY) ? null : SlotPainter.under(menu, mouseX, mouseY, leftPos, topPos);
        hovered = under != null && revealed(under) ? under : null;
        if (covered()) renderTransparentBackground(g);
        else renderContents(g, mouseX, mouseY, partialTick);
        g.nextStratum();
        recipes.render(g, mouseX, mouseY, partialTick);
        g.nextStratum();
        renderCarriedItem(g, mouseX, mouseY);
        renderSnapbackItem(g);
        if (!covered()) drawEffects(g);
        if (!menu.getCarried().isEmpty()) return;
        String filter = recipes.filterTooltip(mouseX, mouseY);
        if (filter != null) {
            InventoryStyle.tooltip(g, List.of(filter), mouseX, mouseY, width, height);
        } else if (recipes.contains(mouseX, mouseY)) {
            recipes.renderTooltip(g, mouseX, mouseY, null, width, height);
        } else {
            SlotPainter.tooltip(g, hovered, menu.getCarried(), this::getTooltipFromContainerItem, mouseX, mouseY, width, height);
            recipes.renderTooltip(g, mouseX, mouseY, hovered, width, height);
        }
    }

    @Override
    protected void renderSlots(GuiGraphics g) {
        super.renderSlots(g);
        recipes.renderGhosts(g);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        InventoryStyle.window(g, x, y, imageWidth, imageHeight);
        drawHeader(g, mouseX, mouseY, tr("blockland.inventory.title"));
        // the character between its equipment
        InventoryStyle.card(g, x + CHARACTER_X, y + EQUIP_Y, CHARACTER_W, CHARACTER_H);
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + CHARACTER_X + 2, y + EQUIP_Y + 4, x + CHARACTER_X + CHARACTER_W - 2,
                y + EQUIP_Y + CHARACTER_H - 4, 30, 0.0625f, xMouse, yMouse, player);
        // crafting in its own card: the grid, an arrow, the result
        InventoryStyle.card(g, x + CARD_X, y + EQUIP_Y, CARD_W, CHARACTER_H);
        Draw.text(g, tr("blockland.inventory.crafting"), x + CARD_X + 5, y + EQUIP_Y + 5, 0.75f, InventoryStyle.dim(), false);
        int ax0 = x + CRAFT_X + 2 * PITCH, ax1 = x + RESULT_X - 3, ay = y + RESULT_Y + CELL / 2;
        g.fill(ax0, ay - 1, ax1 - 3, ay + 1, InventoryStyle.glyph());
        for (int i = 0; i < 4; i++) g.fill(ax1 - 4 + i, ay - 4 + i, ax1 - 3 + i, ay + 4 - i, InventoryStyle.glyph());
        // the inventory, the hotbar
        Draw.text(g, tr("blockland.inventory.title"), x + PAD, y + MAIN_LABEL_Y, 0.8f, InventoryStyle.dim(), false);
        g.fill(x + PAD, y + HOTBAR_Y - 4, x + WIDTH - PAD, y + HOTBAR_Y - 3, InventoryStyle.fade(InventoryStyle.line(), 0.7f));
        drawBackpackButton(g, mouseX, mouseY);
        // the backpack's storage, as far as the panel has opened
        if (imageHeight > BASE_HEIGHT + 4) {
            g.fill(x + PAD, y + BASE_HEIGHT - 2, x + WIDTH - PAD, y + BASE_HEIGHT - 1, InventoryStyle.line());
            ItemStack worn = Backpacks.worn(player);
            if (!worn.isEmpty() && imageHeight > STORAGE_LABEL_Y + 10) {
                UiFont.draw(g, InventoryStyle.readable(net.minecraft.locale.Language.getInstance().getVisualOrder(worn.getHoverName())),
                        x + PAD, y + STORAGE_LABEL_Y, 0.8f, InventoryStyle.dim(), false);
                String used = Backpacks.usedSlots(worn) + " / " + Backpacks.capacity(worn);
                Draw.text(g, used, x + WIDTH - PAD - UiFont.width(used) * 0.75f, y + STORAGE_LABEL_Y + 1, 0.75f, InventoryStyle.dim(), false);
            }
        }
        drawCells(g);
    }

    /** The cell under every slot shown, eased hover, a glyph in empty equipment slots. */
    private void drawCells(GuiGraphics g) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive() || slot.x < 0 || !revealed(slot)) continue;
            int cx = leftPos + slot.x - 2, cy = topPos + slot.y - 2;
            float h = painter.hover(slot, slot == hovered);
            InventoryStyle.cell(g, cx, cy, CELL, h);
            if (i == InventoryMenu.RESULT_SLOT) {
                InventoryStyle.round(g, cx - 1, cy - 1, CELL + 2, 1, 0, InventoryStyle.fade(InventoryStyle.strong(), 0.4f));
                InventoryStyle.round(g, cx - 1, cy + CELL, CELL + 2, 1, 0, InventoryStyle.fade(InventoryStyle.strong(), 0.4f));
            }
            InventoryStyle.Glyph glyph = InventoryLayout.glyph(i);
            if (glyph != null && !slot.hasItem()) InventoryStyle.glyph(g, glyph, cx + 2, cy + 2, InventoryStyle.glyph());
        }
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY, String title) {
        Draw.text(g, title, leftPos + PAD, topPos + 9, 1f, InventoryStyle.text(), false);
        // the recipe book's switch, beside the title
        bookHover.setTarget(overBookButton(mouseX, mouseY) ? 1 : 0);
        InventoryStyle.button(g, bookButtonX(), topPos + 6, BOOK_BUTTON_W, 14, tr("blockland.inventory.recipe_book"), bookHover.get(),
                recipes.visible());
        int bx = leftPos + imageWidth - PAD - 54, by = topPos + 11;
        String label = tr("blockland.inventory.brightness");
        Draw.text(g, label, bx - 6 - UiFont.width(label) * 0.75f, by - 2, 0.75f, InventoryStyle.dim(), false);
        brightHover.setTarget(draggingBrightness || overBrightness(mouseX, mouseY) ? 1 : 0);
        InventoryStyle.brightnessBar(g, bx, by, 54, 3, brightHover.get());
    }

    private void drawBackpackButton(GuiGraphics g, int mouseX, int mouseY) {
        if (Backpacks.worn(player).isEmpty()) return;
        boolean over = inside(mouseX, mouseY, leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H);
        buttonHover.setTarget(over ? 1 : 0);
        InventoryStyle.button(g, leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H,
                tr(storageShown() ? "blockland.inventory.backpack_close" : "blockland.inventory.backpack_open"), buttonHover.get(), storageShown());
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

    // ---- input -----------------------------------------------------------------------------------------

    private static final int BOOK_BUTTON_W = 50;

    private int bookButtonX() {
        return leftPos + PAD + UiFont.width(tr("blockland.inventory.title")) + 8;
    }

    private boolean overBookButton(double mouseX, double mouseY) {
        return !covered() && inside(mouseX, mouseY, bookButtonX(), topPos + 6, BOOK_BUTTON_W, 14);
    }

    private boolean overBrightness(double mouseX, double mouseY) {
        return inside(mouseX, mouseY, leftPos + imageWidth - PAD - 56, topPos + 6, 58, 12);
    }

    /** The header's free space (its controls are tested first): held there, the panel is moved. */
    private boolean overHeader(double mouseX, double mouseY) {
        return !covered() && inside(mouseX, mouseY, leftPos, topPos, imageWidth, HEADER);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overBookButton(mouseX, mouseY)) {
            AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
            recipes.toggle();
            layout();
            return true;
        }
        if (recipes.mouseClicked(mouseX, mouseY, button)) return true;
        if (covered()) return true;
        if (button == 0 && !Backpacks.worn(player).isEmpty()
                && inside(mouseX, mouseY, leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H)) {
            setExpanded(!storageShown());
            return true;
        }
        if (button == 0 && overBrightness(mouseX, mouseY)) {
            draggingBrightness = true;
            setBrightness(mouseX);
            return true;
        }
        if (button == 0 && overHeader(mouseX, mouseY)) {
            drag.press(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingBrightness) {
            setBrightness(mouseX);
            return true;
        }
        if (drag.dragging()) {
            drag.drag(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBrightness) {
            draggingBrightness = false;
            ClientSettings.save();
            return true;
        }
        if (drag.dragging()) {
            drag.release();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return recipes.mouseScrolled(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return recipes.keyPressed(key, scan, modifiers) || super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        recipes.keyReleased();
        return super.keyReleased(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return recipes.charTyped(c, modifiers) || super.charTyped(c, modifiers);
    }

    @Override
    protected boolean isHovering(int x, int y, int w, int h, double mouseX, double mouseY) {
        return !covered() && super.isHovering(x, y, w, h, mouseX, mouseY);
    }

    /** The book's card is not outside the screen (a click there would throw the carried stack). */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        return !recipes.contains(mouseX, mouseY) && super.hasClickedOutside(mouseX, mouseY, left, top, button);
    }

    @Override
    protected void slotClicked(Slot slot, int index, int button, ClickType type) {
        super.slotClicked(slot, index, button, type);
        recipes.slotClicked(slot);
    }

    @Override
    public void recipesUpdated() {
        recipes.recipesUpdated();
    }

    @Override
    public void fillGhostRecipe(RecipeDisplay display) {
        recipes.fillGhostRecipe(display);
    }

    private void setBrightness(double mouseX) {
        InventoryStyle.setBrightness((float) ((mouseX - (leftPos + imageWidth - PAD - 54)) / 53.0));
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
