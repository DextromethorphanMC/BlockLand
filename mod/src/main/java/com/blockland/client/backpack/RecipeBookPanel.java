package com.blockland.client.backpack;

import com.blockland.anim.Spring;
import com.blockland.client.font.UiFont;
import com.blockland.client.render.RenderClock;
import com.blockland.client.ui.Draw;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.ExtendedRecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.blockland.client.ui.Tr.tr;

/**
 * The survival inventory's recipe book (UI spec §1): vanilla's crafting recipe book — the player's {@link ClientRecipeBook}
 * and its {@link RecipeCollection}s, the session's recipe search tree, the 2×2 grid's fit, placing through
 * {@code handlePlaceRecipe} (shift: as many as possible), the ghost recipe the server answers with, the open and
 * "craftable only" settings saved in the book and sent to the server, new-recipe highlights — behaving as vanilla's
 * {@code RecipeBookComponent} / {@code RecipeBookPage} / {@code RecipeButton} / {@code OverlayRecipeComponent} /
 * {@code GhostSlots} do, but laid out and drawn by BlockLand (greys only, no vanilla sprite): a card beside the inventory
 * with the category tabs, the search field and the filter on top, a grid of recipes, paging under it, the alternatives
 * overlay on a right click.
 */
final class RecipeBookPanel {
    /** The card's size; the gap to the inventory beside it. */
    static final int W = 150, H = InventoryLayout.BASE_HEIGHT, GAP = 8;
    private static final int PAD = 12, CELL = 22, PITCH = 24, COLS = 5;
    private static final int TABS_Y = 26, TAB_W = 24, TAB_H = 20, SEARCH_Y = 50, SEARCH_H = 16, GRID_Y = 74;
    private static final int GRID_X = (W - COLS * PITCH + 2) / 2;
    /** Rows of recipes a page holds (vanilla's 4 at its smaller card). */
    private static final int ROWS = (H - GRID_Y - 30) / PITCH;
    private static final int PER_PAGE = ROWS * COLS;
    /** Ticks a cycling ingredient or result shows (vanilla's). */
    private static final float TICKS_TO_SWAP = 30;
    private static final float PULSE = 15;
    private static final RecipeBookType TYPE = RecipeBookType.CRAFTING;

    /** A category tab (vanilla's crafting tabs, in its order). */
    private record Tab(ItemStack icon, @Nullable ItemStack second, ExtendedRecipeBookCategory category) {
    }

    private static final List<Tab> TABS = List.of(
            new Tab(new ItemStack(Items.COMPASS), null, SearchRecipeBookCategory.CRAFTING),
            new Tab(new ItemStack(Items.IRON_AXE), new ItemStack(Items.GOLDEN_SWORD), RecipeBookCategories.CRAFTING_EQUIPMENT),
            new Tab(new ItemStack(Items.BRICKS), null, RecipeBookCategories.CRAFTING_BUILDING_BLOCKS),
            new Tab(new ItemStack(Items.LAVA_BUCKET), new ItemStack(Items.APPLE), RecipeBookCategories.CRAFTING_MISC),
            new Tab(new ItemStack(Items.REDSTONE), null, RecipeBookCategories.CRAFTING_REDSTONE));

    /** A recipe button's recipes as vanilla's {@code RecipeButton} resolves them. */
    private record Entry(RecipeDisplayId id, List<ItemStack> items) {
        ItemStack item(int i) {
            return items.isEmpty() ? ItemStack.EMPTY : items.get(i % items.size());
        }
    }

    private static final class Button {
        final RecipeCollection collection;
        final List<Entry> entries;
        final boolean sameResult;
        float pulse;

        Button(RecipeCollection collection, List<Entry> entries, boolean sameResult) {
            this.collection = collection;
            this.entries = entries;
            this.sameResult = sameResult;
        }
    }

    /** One recipe of the alternatives overlay: its grid's ingredients at their cells. */
    private record OverlayEntry(RecipeDisplayId id, boolean craftable, List<int[]> cells, List<List<ItemStack>> items) {
    }

    /** A ghost slot (vanilla's {@code GhostSlots}): what the recipe wants there, cycling. */
    private record Ghost(List<ItemStack> items, boolean result) {
        ItemStack item(int i) {
            return items.isEmpty() ? ItemStack.EMPTY : items.get(i % items.size());
        }
    }

    private final InventoryMenu menu;
    private Minecraft mc;
    private ClientRecipeBook book;
    private final StackedItemContents stacked = new StackedItemContents();
    private int timesChanged;
    private boolean visible, narrow;
    private int x, y;
    private float time;
    private int selectedTab;
    private final boolean[] tabShown = new boolean[TABS.size()];
    private final float[] tabPulse = new float[TABS.size()];
    @Nullable
    private EditBox search;
    private String lastSearch = "";
    private boolean ignoreTextInput;
    private List<RecipeCollection> collections = List.of();
    private final List<Button> buttons = new ArrayList<>();
    private int page, pages;
    private boolean filtering;
    @Nullable
    private RecipeDisplayId lastPlaced, lastRecipe;
    @Nullable
    private RecipeCollection lastCollection;
    private final Map<Slot, Ghost> ghosts = new IdentityHashMap<>();
    // the alternatives overlay
    private boolean overlayShown;
    private int overlayX, overlayY, overlayCols;
    private RecipeCollection overlayCollection = RecipeCollection.EMPTY;
    private final List<OverlayEntry> overlay = new ArrayList<>();
    // hover easing
    private final Map<Object, Spring> hover = new IdentityHashMap<>();
    private final Object filterKey = new Object(), backKey = new Object(), forwardKey = new Object();
    @Nullable
    private Button hoveredButton;

    RecipeBookPanel(InventoryMenu menu) {
        this.menu = menu;
    }

    // ---- state (vanilla RecipeBookComponent) --------------------------------------------------------------

    void init(Minecraft mc, boolean narrow) {
        this.mc = mc;
        this.narrow = narrow;
        this.book = mc.player.getRecipeBook();
        this.timesChanged = mc.player.getInventory().getTimesChanged();
        this.visible = book.isOpen(TYPE);
        if (visible) initVisuals();
    }

    private void initVisuals() {
        filtering = book.isFiltering(TYPE);
        stacked.clear();
        mc.player.getInventory().fillStackedContents(stacked);
        menu.fillCraftSlotsStackedContents(stacked);
        String value = search != null ? search.getValue() : "";
        search = new EditBox(mc.font, 0, 0, W - 2 * PAD - 20, SEARCH_H, Component.translatable("itemGroup.search"));
        search.setMaxLength(50);
        search.setVisible(true);
        search.setValue(value);
        selectMatching();
        updateTabs();
        updateCollections(false);
    }

    boolean visible() {
        return visible;
    }

    /** Over the inventory (too narrow a screen for both side by side) rather than beside it. */
    boolean narrow() {
        return narrow;
    }

    void place(int x, int y) {
        this.x = x;
        this.y = y;
        if (search != null) {
            search.setX(x + PAD + 18);
            search.setY(y + SEARCH_Y);
        }
    }

    void toggle() {
        setVisible(!visible);
    }

    private void setVisible(boolean shown) {
        if (shown) initVisuals();
        visible = shown;
        book.setOpen(TYPE, shown);
        if (!shown) overlayShown = false;
        sendSettings();
    }

    private void sendSettings() {
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            connection.send(new ServerboundRecipeBookChangeSettingsPacket(TYPE, book.getBookSettings().isOpen(TYPE),
                    book.getBookSettings().isFiltering(TYPE)));
        }
    }

    void tick() {
        boolean open = book.isOpen(TYPE);
        if (visible != open) setVisible(open);
        if (visible && timesChanged != mc.player.getInventory().getTimesChanged()) {
            updateStacked();
            timesChanged = mc.player.getInventory().getTimesChanged();
        }
    }

    private void updateStacked() {
        stacked.clear();
        mc.player.getInventory().fillStackedContents(stacked);
        menu.fillCraftSlotsStackedContents(stacked);
        selectMatching();
        updateCollections(false);
    }

    /** A click on a slot: on the crafting grid or its result the ghost recipe goes and the counts are taken again. */
    void slotClicked(@Nullable Slot slot) {
        if (slot != null && (slot == menu.getResultSlot() || menu.getInputGridSlots().contains(slot))) {
            lastPlaced = null;
            ghosts.clear();
            if (visible) updateStacked();
        }
    }

    void recipesUpdated() {
        selectMatching();
        updateTabs();
        if (visible) updateCollections(false);
    }

    /** What fits the 2×2 grid (vanilla's {@code CraftingRecipeBookComponent.canDisplay}). */
    private boolean canDisplay(RecipeDisplay display) {
        int w = menu.getGridWidth(), h = menu.getGridHeight();
        return switch (display) {
            case ShapedCraftingRecipeDisplay shaped -> w >= shaped.width() && h >= shaped.height();
            case ShapelessCraftingRecipeDisplay shapeless -> w * h >= shapeless.ingredients().size();
            default -> false;
        };
    }

    private void selectMatching() {
        for (Tab tab : TABS) {
            for (RecipeCollection collection : book.getCollection(tab.category())) collection.selectRecipes(stacked, this::canDisplay);
        }
    }

    private void updateTabs() {
        RecipeCollection.CraftableStatus status = filtering ? RecipeCollection.CraftableStatus.CRAFTABLE : RecipeCollection.CraftableStatus.ANY;
        for (int i = 0; i < TABS.size(); i++) {
            Tab tab = TABS.get(i);
            if (tab.category() instanceof SearchRecipeBookCategory) {
                tabShown[i] = true;
                continue;
            }
            tabShown[i] = false;
            for (RecipeCollection collection : book.getCollection(tab.category())) {
                if (collection.hasAnySelected()) {
                    tabShown[i] = true;
                    break;
                }
            }
            if (!tabShown[i]) continue;
            // a tab holding a recipe not seen yet pulses
            outer:
            for (RecipeCollection collection : book.getCollection(tab.category())) {
                for (RecipeDisplayEntry entry : collection.getSelectedRecipes(status)) {
                    if (book.willHighlight(entry.id())) {
                        tabPulse[i] = PULSE;
                        break outer;
                    }
                }
            }
        }
    }

    private void updateCollections(boolean firstPage) {
        List<RecipeCollection> list = new ArrayList<>(book.getCollection(TABS.get(selectedTab).category()));
        list.removeIf(c -> !c.hasAnySelected());
        String value = search == null ? "" : search.getValue();
        if (!value.isEmpty()) {
            ClientPacketListener connection = mc.getConnection();
            if (connection != null) {
                Set<RecipeCollection> found = new ObjectLinkedOpenHashSet<>(connection.searchTrees().recipes().search(value.toLowerCase(Locale.ROOT)));
                list.removeIf(c -> !found.contains(c));
            }
        }
        if (filtering) list.removeIf(c -> !c.hasCraftable());
        collections = list;
        pages = (int) Math.ceil(list.size() / (double) PER_PAGE);
        if (pages <= page || firstPage) page = 0;
        updatePage();
    }

    /** The recipe buttons of this page (vanilla's {@code RecipeButton.init}), seen recipes no longer highlighted. */
    private void updatePage() {
        buttons.clear();
        ContextMap context = SlotDisplayContext.fromLevel(mc.level);
        RecipeCollection.CraftableStatus status = filtering ? RecipeCollection.CraftableStatus.CRAFTABLE : RecipeCollection.CraftableStatus.ANY;
        for (int i = page * PER_PAGE; i < Math.min(collections.size(), (page + 1) * PER_PAGE); i++) {
            RecipeCollection collection = collections.get(i);
            List<RecipeDisplayEntry> selected = collection.getSelectedRecipes(status);
            List<Entry> entries = selected.stream().map(e -> new Entry(e.id(), e.resultItems(context))).toList();
            Button button = new Button(collection, entries, sameResult(entries));
            List<RecipeDisplayId> highlighted = selected.stream().map(RecipeDisplayEntry::id).filter(book::willHighlight).toList();
            if (!highlighted.isEmpty()) {
                highlighted.forEach(mc.player::removeRecipeHighlight);
                button.pulse = PULSE;
            }
            buttons.add(button);
        }
    }

    private static boolean sameResult(List<Entry> entries) {
        Iterator<ItemStack> it = entries.stream().flatMap(e -> e.items().stream()).iterator();
        if (!it.hasNext()) return true;
        ItemStack first = it.next();
        while (it.hasNext()) {
            if (!ItemStack.isSameItemSameComponents(first, it.next())) return false;
        }
        return true;
    }

    private int cycle() {
        return Mth.floor(time / TICKS_TO_SWAP);
    }

    private RecipeDisplayId current(Button button) {
        return button.entries.get(cycle() % button.entries.size()).id();
    }

    private ItemStack display(Button button) {
        int i = cycle(), n = button.entries.size();
        return button.entries.get(i % n).item(i / n);
    }

    private boolean tryPlace(RecipeCollection collection, RecipeDisplayId id) {
        if (!collection.isCraftable(id) && id.equals(lastPlaced)) return false;
        lastPlaced = id;
        ghosts.clear();
        mc.gameMode.handlePlaceRecipe(mc.player.containerMenu.containerId, id, Screen.hasShiftDown());
        return true;
    }

    // ---- the ghost recipe (vanilla GhostSlots) ----------------------------------------------------------

    void fillGhostRecipe(RecipeDisplay display) {
        ghosts.clear();
        ContextMap context = SlotDisplayContext.fromLevel(Objects.requireNonNull(mc.level));
        ghost(menu.getResultSlot(), context, display.result(), true);
        List<Slot> grid = menu.getInputGridSlots();
        switch (display) {
            case ShapedCraftingRecipeDisplay shaped -> PlaceRecipeHelper.placeRecipe(menu.getGridWidth(), menu.getGridHeight(),
                    shaped.width(), shaped.height(), shaped.ingredients(), (ingredient, index, gx, gy) -> ghost(grid.get(index), context, ingredient, false));
            case ShapelessCraftingRecipeDisplay shapeless -> {
                int n = Math.min(shapeless.ingredients().size(), grid.size());
                for (int i = 0; i < n; i++) ghost(grid.get(i), context, shapeless.ingredients().get(i), false);
            }
            default -> {
            }
        }
    }

    private void ghost(Slot slot, ContextMap context, SlotDisplay display, boolean result) {
        List<ItemStack> items = display.resolveForStacks(context);
        if (!items.isEmpty()) ghosts.put(slot, new Ghost(items, result));
    }

    /** The ghost recipe over the slots (in the container's own coordinates, as slots are drawn). */
    void renderGhosts(GuiGraphics g) {
        ghosts.forEach((slot, ghost) -> {
            int sx = slot.x, sy = slot.y;
            int tint = InventoryStyle.fade(InventoryStyle.strong(), 0.22f);
            if (ghost.result()) InventoryStyle.round(g, sx - 3, sy - 3, 22, 22, 3, tint);
            else InventoryStyle.round(g, sx - 1, sy - 1, 18, 18, 3, tint);
            ItemStack item = ghost.item(cycle());
            g.renderFakeItem(item, sx, sy);
            // faded: what is still wanted, not what is there
            InventoryStyle.round(g, sx - 1, sy - 1, 18, 18, 3, InventoryStyle.fade(InventoryStyle.panel(), 0.55f));
            if (ghost.result()) InventoryStyle.decorations(g, item, sx, sy, null);
        });
    }

    // ---- drawing --------------------------------------------------------------------------------------

    private float hover(Object key, boolean over) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(420, 30));
        spring.setTarget(over ? 1 : 0);
        return Mth.clamp(spring.get(), 0, 1);
    }

    private int tabX(int index) {
        int shown = 0, at = 0;
        for (int i = 0; i < TABS.size(); i++) {
            if (!tabShown[i]) continue;
            if (i == index) at = shown;
            shown++;
        }
        int row = shown * (TAB_W + 2) - 2;
        return x + (W - row) / 2 + at * (TAB_W + 2);
    }

    private int cellX(int i) {
        return x + GRID_X + (i % COLS) * PITCH;
    }

    private int cellY(int i) {
        return y + GRID_Y + (i / COLS) * PITCH;
    }

    void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        if (!Screen.hasControlDown()) time += partialTick;
        InventoryStyle.window(g, x, y, W, H);
        // the title and the filter
        Draw.text(g, tr("blockland.inventory.recipe_book"), x + PAD, y + 9, 1f, InventoryStyle.text(), false);
        String filter = tr(filtering ? "blockland.inventory.recipes_craftable" : "blockland.inventory.recipes_all");
        int fw = Math.round(UiFont.width(filter) * 0.8f) + 12, fx = x + W - PAD - fw;
        InventoryStyle.button(g, fx, y + 6, fw, 14, filter, hover(filterKey, overFilter(mouseX, mouseY)), filtering);
        // the category tabs
        for (int i = 0; i < TABS.size(); i++) {
            if (!tabShown[i]) continue;
            Tab tab = TABS.get(i);
            int tx = tabX(i), ty = y + TABS_Y;
            boolean on = i == selectedTab;
            float h = hover(tab, ModernInventoryScreen.inside(mouseX, mouseY, tx, ty, TAB_W, TAB_H));
            InventoryStyle.round(g, tx, ty, TAB_W, TAB_H, 4,
                    on ? InventoryStyle.cellHover() : InventoryStyle.fade(InventoryStyle.cellHover(), 0.15f + 0.85f * h));
            if (on) InventoryStyle.round(g, tx + 5, ty + TAB_H - 2, TAB_W - 10, 2, 1, InventoryStyle.strong());
            float lift = tabPulse[i] > 0 ? (float) Math.sin(tabPulse[i] / PULSE * Math.PI) * 2 : 0;
            if (tabPulse[i] > 0) tabPulse[i] = Math.max(0, tabPulse[i] - partialTick);
            g.pose().pushMatrix();
            g.pose().translate(0, -lift);
            if (tab.second() == null) {
                g.renderFakeItem(tab.icon(), tx + (TAB_W - 16) / 2, ty + 2);
            } else {
                g.pose().pushMatrix();
                g.pose().translate(tx + 2, ty + 3);
                g.pose().scale(0.75f, 0.75f);
                g.renderFakeItem(tab.icon(), 0, 0);
                g.renderFakeItem(tab.second(), 10, 6);
                g.pose().popMatrix();
            }
            g.pose().popMatrix();
        }
        drawSearch(g);
        // the recipes of this page
        hoveredButton = null;
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            int cx = cellX(i), cy = cellY(i);
            boolean over = !overlayShown && ModernInventoryScreen.inside(mouseX, mouseY, cx, cy, CELL, CELL);
            if (over) hoveredButton = button;
            drawButton(g, button, cx, cy, hover(button.collection, over), partialTick);
        }
        if (buttons.isEmpty()) {
            String none = tr("blockland.inventory.recipes_none");
            Draw.text(g, none, x + (W - UiFont.width(none) * 0.8f) / 2, y + GRID_Y + 20, 0.8f, InventoryStyle.dim(), false);
        }
        // paging
        if (pages > 1) {
            int py = y + H - 22;
            String label = (page + 1) + " / " + pages;
            Draw.text(g, label, x + (W - UiFont.width(label) * 0.8f) / 2, py + 4, 0.8f, InventoryStyle.dim(), false);
            if (page > 0) arrow(g, x + PAD + 6, py, false, hover(backKey, overBack(mouseX, mouseY)));
            if (page < pages - 1) arrow(g, x + W - PAD - 20, py, true, hover(forwardKey, overForward(mouseX, mouseY)));
        }
        if (overlayShown) drawOverlay(g, mouseX, mouseY);
    }

    private void drawSearch(GuiGraphics g) {
        int fx = x + PAD, fy = y + SEARCH_Y, fw = W - 2 * PAD;
        InventoryStyle.round(g, fx, fy, fw, SEARCH_H, 4, InventoryStyle.cell());
        InventoryStyle.glyph(g, InventoryStyle.Glyph.SEARCH, fx + 2, fy, InventoryStyle.glyph());
        String value = search == null ? "" : search.getValue();
        float tx = fx + 18, ty = fy + 4;
        if (value.isEmpty()) {
            Draw.text(g, Draw.fit(Component.translatable("gui.recipebook.search_hint").getString(), (fw - 22) / 0.85f), tx, ty, 0.85f,
                    InventoryStyle.dim(), false);
        } else {
            Draw.text(g, Draw.fit(value, (fw - 22) / 0.85f), tx, ty, 0.85f, InventoryStyle.text(), false);
        }
        if (search != null && search.isFocused() && (RenderClock.nanos() / 500_000_000L) % 2 == 0) {
            int cursor = Math.min(search.getCursorPosition(), value.length());
            float cx = tx + UiFont.width(value.substring(0, cursor)) * 0.85f;
            if (cx < fx + fw - 4) g.fill(Math.round(cx), fy + 3, Math.round(cx) + 1, fy + SEARCH_H - 3, InventoryStyle.text());
        }
    }

    /** A recipe: a cell, the result (a second one behind it when it holds several recipes), dimmed when not craftable. */
    private void drawButton(GuiGraphics g, Button button, int cx, int cy, float h, float partialTick) {
        boolean pulsing = button.pulse > 0;
        if (pulsing) {
            float s = 1 + 0.1f * (float) Math.sin(button.pulse / PULSE * Math.PI);
            g.pose().pushMatrix();
            g.pose().translate(cx + CELL / 2f, cy + CELL / 2f);
            g.pose().scale(s, s);
            g.pose().translate(-(cx + CELL / 2f), -(cy + CELL / 2f));
            button.pulse = Math.max(0, button.pulse - partialTick);
        }
        boolean craftable = button.collection.hasCraftable(), several = button.entries.size() > 1;
        InventoryStyle.cell(g, cx, cy, CELL, h);
        if (craftable) InventoryStyle.round(g, cx, cy + CELL - 2, CELL, 2, 1, InventoryStyle.fade(InventoryStyle.strong(), 0.45f));
        ItemStack item = display(button);
        int off = 3;
        if (several && button.sameResult) {
            g.renderItem(item, cx + off + 1, cy + off + 1, 0);
            off--;
        }
        g.renderFakeItem(item, cx + off, cy + off);
        if (!craftable) InventoryStyle.round(g, cx, cy, CELL, CELL, 3, InventoryStyle.fade(InventoryStyle.panel(), 0.6f));
        if (several) {
            // several recipes: a stack mark in the corner
            int mark = InventoryStyle.fade(InventoryStyle.text(), 0.7f);
            g.fill(cx + CELL - 6, cy + CELL - 4, cx + CELL - 2, cy + CELL - 3, mark);
            g.fill(cx + CELL - 4, cy + CELL - 6, cx + CELL - 3, cy + CELL - 2, mark);
        }
        if (pulsing) g.pose().popMatrix();
    }

    private void arrow(GuiGraphics g, int ax, int ay, boolean forward, float h) {
        InventoryStyle.round(g, ax, ay, 14, 14, 4, InventoryStyle.fade(InventoryStyle.cellHover(), 0.2f + 0.8f * h));
        int color = InventoryStyle.text();
        for (int i = 0; i < 4; i++) {
            int col = forward ? ax + 5 + i : ax + 8 - i;
            g.fill(col, ay + 3 + i, col + 1, ay + 11 - i, color);
        }
    }

    // ---- the alternatives overlay (vanilla OverlayRecipeComponent) ------------------------------------------

    private void openOverlay(Button button, int bx, int by) {
        ContextMap context = SlotDisplayContext.fromLevel(mc.level);
        overlayCollection = button.collection;
        overlay.clear();
        List<RecipeDisplayEntry> craftable = button.collection.getSelectedRecipes(RecipeCollection.CraftableStatus.CRAFTABLE);
        List<RecipeDisplayEntry> not = filtering ? List.of() : button.collection.getSelectedRecipes(RecipeCollection.CraftableStatus.NOT_CRAFTABLE);
        int total = craftable.size() + not.size();
        overlayCols = total <= 16 ? 4 : 5;
        int rows = (int) Math.ceil(total / (double) overlayCols);
        for (int i = 0; i < total; i++) {
            boolean can = i < craftable.size();
            RecipeDisplayEntry entry = can ? craftable.get(i) : not.get(i - craftable.size());
            List<int[]> cells = new ArrayList<>();
            List<List<ItemStack>> items = new ArrayList<>();
            switch (entry.display()) {
                case ShapedCraftingRecipeDisplay shaped -> PlaceRecipeHelper.placeRecipe(3, 3, shaped.width(), shaped.height(), shaped.ingredients(),
                        (ingredient, index, gx, gy) -> {
                            List<ItemStack> stacks = ingredient.resolveForStacks(context);
                            if (!stacks.isEmpty()) {
                                cells.add(new int[]{gx, gy});
                                items.add(stacks);
                            }
                        });
                case ShapelessCraftingRecipeDisplay shapeless -> {
                    for (int k = 0; k < shapeless.ingredients().size(); k++) {
                        List<ItemStack> stacks = shapeless.ingredients().get(k).resolveForStacks(context);
                        if (!stacks.isEmpty()) {
                            cells.add(new int[]{k % 3, k / 3});
                            items.add(stacks);
                        }
                    }
                }
                default -> {
                }
            }
            overlay.add(new OverlayEntry(entry.id(), can, cells, items));
        }
        // beside the button, kept on the card as vanilla keeps it near the book's middle
        int w = Math.min(total, overlayCols) * PITCH + 8, h = rows * PITCH + 8;
        overlayX = Mth.clamp(bx, x + 4, x + W - 4 - w);
        overlayY = Mth.clamp(by, y + 4, y + H - 4 - h);
        if (overlayX < x + 4) overlayX = bx;
        overlayShown = true;
    }

    private int[] overlayCell(int i) {
        return new int[]{overlayX + 4 + (i % overlayCols) * PITCH, overlayY + 4 + (i / overlayCols) * PITCH};
    }

    private void drawOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.nextStratum();
        int rows = (int) Math.ceil(overlay.size() / (double) overlayCols);
        int w = Math.min(overlay.size(), overlayCols) * PITCH + 8, h = rows * PITCH + 8;
        InventoryStyle.shadow(g, overlayX, overlayY, w, h, 5);
        InventoryStyle.framed(g, overlayX, overlayY, w, h, 5, InventoryStyle.raised(), InventoryStyle.line());
        for (int i = 0; i < overlay.size(); i++) {
            OverlayEntry entry = overlay.get(i);
            int[] c = overlayCell(i);
            boolean over = ModernInventoryScreen.inside(mouseX, mouseY, c[0], c[1], CELL, CELL);
            InventoryStyle.cell(g, c[0], c[1], CELL, hover(entry, over));
            for (int k = 0; k < entry.cells().size(); k++) {
                int[] cell = entry.cells().get(k);
                List<ItemStack> stacks = entry.items().get(k);
                g.pose().pushMatrix();
                g.pose().translate(c[0] + 2 + 3 + cell[0] * 7, c[1] + 2 + 3 + cell[1] * 7);
                g.pose().scale(0.375f, 0.375f);
                g.pose().translate(-8, -8);
                g.renderItem(stacks.get(cycle() % stacks.size()), 0, 0);
                g.pose().popMatrix();
            }
            if (!entry.craftable()) InventoryStyle.round(g, c[0], c[1], CELL, CELL, 3, InventoryStyle.fade(InventoryStyle.panel(), 0.6f));
        }
    }

    // ---- tooltips -----------------------------------------------------------------------------------------

    /** The recipe under the mouse, or the ghost item of an empty slot under it. */
    void renderTooltip(GuiGraphics g, int mouseX, int mouseY, @Nullable Slot hovered, int screenW, int screenH) {
        if (!visible) return;
        if (hoveredButton != null) {
            ItemStack item = display(hoveredButton);
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, item));
            if (hoveredButton.entries.size() > 1) lines.add(Component.translatable("gui.recipebook.moreRecipes"));
            InventoryStyle.tooltip(g, lines, null, mouseX, mouseY, screenW, screenH);
            return;
        }
        if (hovered != null && !hovered.hasItem()) {
            Ghost ghost = ghosts.get(hovered);
            if (ghost != null) {
                ItemStack item = ghost.item(cycle());
                InventoryStyle.tooltip(g, Screen.getTooltipFromItem(mc, item), null, mouseX, mouseY, screenW, screenH);
            }
        }
    }

    /** The filter's tooltip (vanilla's words), when the mouse is on it. */
    @Nullable
    String filterTooltip(int mouseX, int mouseY) {
        if (!visible || !overFilter(mouseX, mouseY)) return null;
        return Component.translatable(filtering ? "gui.recipebook.toggleRecipes.craftable" : "gui.recipebook.toggleRecipes.all").getString();
    }

    // ---- input ----------------------------------------------------------------------------------------------

    boolean contains(double mouseX, double mouseY) {
        return visible && ModernInventoryScreen.inside(mouseX, mouseY, x, y, W, H);
    }

    private boolean overFilter(double mouseX, double mouseY) {
        String filter = tr(filtering ? "blockland.inventory.recipes_craftable" : "blockland.inventory.recipes_all");
        int fw = Math.round(UiFont.width(filter) * 0.8f) + 12;
        return ModernInventoryScreen.inside(mouseX, mouseY, x + W - PAD - fw, y + 6, fw, 14);
    }

    private boolean overBack(double mouseX, double mouseY) {
        return pages > 1 && page > 0 && ModernInventoryScreen.inside(mouseX, mouseY, x + PAD + 6, y + H - 22, 14, 14);
    }

    private boolean overForward(double mouseX, double mouseY) {
        return pages > 1 && page < pages - 1 && ModernInventoryScreen.inside(mouseX, mouseY, x + W - PAD - 20, y + H - 22, 14, 14);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || mc.player.isSpectator()) return false;
        RecipeDisplayId clicked = null;
        RecipeCollection clickedCollection = null;
        boolean onPage = false;
        if (overlayShown) {
            onPage = true;
            if (button == 0) {
                for (int i = 0; i < overlay.size(); i++) {
                    int[] c = overlayCell(i);
                    if (ModernInventoryScreen.inside(mouseX, mouseY, c[0], c[1], CELL, CELL)) {
                        clicked = overlay.get(i).id();
                        clickedCollection = overlayCollection;
                        break;
                    }
                }
            }
            if (clicked == null) overlayShown = false;
        } else if (overForward(mouseX, mouseY) && button == 0) {
            page++;
            updatePage();
            onPage = true;
        } else if (overBack(mouseX, mouseY) && button == 0) {
            page--;
            updatePage();
            onPage = true;
        } else {
            for (int i = 0; i < buttons.size(); i++) {
                if (!ModernInventoryScreen.inside(mouseX, mouseY, cellX(i), cellY(i), CELL, CELL)) continue;
                if (button != 0 && button != 1) break;
                Button b = buttons.get(i);
                AbstractWidget.playButtonClickSound(mc.getSoundManager());
                if (button == 0) {
                    clicked = current(b);
                    clickedCollection = b.collection;
                } else if (b.entries.size() != 1) {
                    openOverlay(b, cellX(i), cellY(i));
                }
                onPage = true;
                break;
            }
        }
        if (onPage) {
            if (clicked != null && clickedCollection != null) {
                if (!tryPlace(clickedCollection, clicked)) return false;
                lastCollection = clickedCollection;
                lastRecipe = clicked;
                // over the inventory it closes once a recipe is placed (as vanilla's)
                if (narrow) setVisible(false);
            }
            return true;
        }
        if (search != null) {
            boolean field = ModernInventoryScreen.inside(mouseX, mouseY, x + PAD, y + SEARCH_Y, W - 2 * PAD, SEARCH_H);
            if (field) {
                search.setFocused(true);
                if (mouseX >= search.getX()) search.mouseClicked(mouseX, mouseY, button);
                return true;
            }
            search.setFocused(false);
        }
        if (button == 0 && overFilter(mouseX, mouseY)) {
            AbstractWidget.playButtonClickSound(mc.getSoundManager());
            filtering = !book.isFiltering(TYPE);
            book.setFiltering(TYPE, filtering);
            sendSettings();
            updateCollections(false);
            return true;
        }
        if (button == 0) {
            for (int i = 0; i < TABS.size(); i++) {
                if (!tabShown[i] || !ModernInventoryScreen.inside(mouseX, mouseY, tabX(i), y + TABS_Y, TAB_W, TAB_H)) continue;
                AbstractWidget.playButtonClickSound(mc.getSoundManager());
                if (selectedTab != i) {
                    selectedTab = i;
                    updateCollections(true);
                }
                return true;
            }
        }
        // anything else on the card is the card's
        return contains(mouseX, mouseY);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!contains(mouseX, mouseY) || overlayShown || pages <= 1) return false;
        int next = Mth.clamp(page - (int) Math.signum(scrollY), 0, pages - 1);
        if (next != page) {
            page = next;
            updatePage();
        }
        return true;
    }

    boolean keyPressed(int key, int scan, int modifiers) {
        ignoreTextInput = false;
        if (!visible || mc.player.isSpectator() || search == null) return false;
        if (key == 256 && narrow) {
            setVisible(false);
            return true;
        }
        if (search.keyPressed(key, scan, modifiers)) {
            checkSearch();
            return true;
        }
        if (search.isFocused() && key != 256) return true;
        if (mc.options.keyChat.matches(key, scan) && !search.isFocused()) {
            ignoreTextInput = true;
            search.setFocused(true);
            return true;
        }
        if (CommonInputs.selected(key) && lastCollection != null && lastRecipe != null) {
            AbstractWidget.playButtonClickSound(mc.getSoundManager());
            return tryPlace(lastCollection, lastRecipe);
        }
        return false;
    }

    void keyReleased() {
        ignoreTextInput = false;
    }

    boolean charTyped(char c, int modifiers) {
        if (ignoreTextInput || !visible || mc.player.isSpectator() || search == null) return false;
        if (search.charTyped(c, modifiers)) {
            checkSearch();
            return true;
        }
        return false;
    }

    private void checkSearch() {
        String value = search.getValue().toLowerCase(Locale.ROOT);
        pirateSpeech(value);
        if (!value.equals(lastSearch)) {
            updateCollections(false);
            lastSearch = value;
        }
    }

    /** Vanilla's easter egg, kept: searching "excitedze" switches to Pirate Speak. */
    private void pirateSpeech(String value) {
        if (!"excitedze".equals(value)) return;
        LanguageManager languages = mc.getLanguageManager();
        LanguageInfo pirate = languages.getLanguage("en_pt");
        if (pirate == null || languages.getSelected().equals("en_pt")) return;
        languages.setSelected("en_pt");
        mc.options.languageCode = "en_pt";
        mc.reloadResourcePacks();
        mc.options.save();
    }
}
