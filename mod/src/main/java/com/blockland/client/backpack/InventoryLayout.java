package com.blockland.client.backpack;

import com.blockland.backpack.BackpackMenus;
import com.blockland.mixin.inventory.SlotAccess;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;

/**
 * Where the player's inventory goes on the inventory screens (UI spec §1), from the content area's corner; the survival
 * screen and the creative screen's inventory tab share it. Cells are 20 px on a 22 px pitch (the item sits 2 in) at the
 * natural size; the grip in the panel's corner changes the cell size ({@link #setCell}, 18–40 px) and the panel's width
 * and height with it — items fill their cells, labels and buttons keep their size.
 *
 * <pre>
 *  header  ─ title ──────────────────────────────── brightness ─
 *  [helm]  ┌ player ┐ [pack]   ┌ crafting ─────────────┐
 *  [chest] │        │          │ [ ][ ]  →  [result]   │   (creative: the destroy-item slot instead)
 *  [legs]  │        │          │ [ ][ ]                │
 *  [boots] └────────┘ [hand]   └───────────────────────┘
 *  物品栏 ─────────────────────────────── [打开背包]
 *  9 × 3 main grid
 *  ─────────────
 *  9 hotbar
 *  ─ backpack name ─ (when open) rows of 9
 * </pre>
 */
final class InventoryLayout {
    static final int PAD = 14, HEADER = 26;
    /** The cells' size range (the grip in the corner resizes the panel by it) and the natural one. */
    static final int MIN_CELL = 18, MAX_CELL = 40, DEFAULT_CELL = 20;
    /** Labels and buttons keep their size; everything holding items follows the cell size. */
    static final int EQUIP_Y = 34;
    static final int BUTTON_W = 64, BUTTON_H = 14;
    static final int HIDDEN = -10000;

    // set from the cell size by setCell (the values below are the natural size's)
    static int CELL, PITCH;
    /** Width of the inventory's content (9 cells and the padding). */
    static int WIDTH;
    static int CHARACTER_X, CHARACTER_W, CHARACTER_H;
    static int SIDE_X;
    static int CARD_X, CARD_W;
    static int CRAFT_X, CRAFT_Y;
    static int RESULT_X, RESULT_Y;
    static int MAIN_LABEL_Y, MAIN_Y, HOTBAR_Y, BASE_HEIGHT, STORAGE_LABEL_Y, STORAGE_Y;
    /** The 打开背包 / 收起背包 button. */
    static int BUTTON_X, BUTTON_Y;
    /** The creative inventory's destroy-item cell, where crafting is in survival. */
    static int TRASH_X, TRASH_Y;

    static {
        setCell(DEFAULT_CELL);
    }

    private InventoryLayout() {
    }

    /** Lays everything out for cells of {@code cell} pixels (on a pitch two more); a no-op when it already is. */
    static void setCell(int cell) {
        cell = Math.max(MIN_CELL, Math.min(MAX_CELL, cell));
        if (cell == CELL) return;
        CELL = cell;
        PITCH = cell + 2;
        WIDTH = PAD * 2 + 9 * PITCH - 2;
        CHARACTER_X = PAD + CELL + 6;
        CHARACTER_W = Math.round(52 * CELL / 20f);
        CHARACTER_H = 4 * PITCH - 2;
        SIDE_X = CHARACTER_X + CHARACTER_W + 6;
        CARD_X = SIDE_X + CELL + 6;
        CARD_W = WIDTH - PAD - CARD_X;
        CRAFT_X = CARD_X + 4;
        CRAFT_Y = EQUIP_Y + 18;
        RESULT_X = CARD_X + CARD_W - 4 - CELL;
        RESULT_Y = CRAFT_Y + (2 * PITCH - 2 - CELL) / 2;
        MAIN_LABEL_Y = EQUIP_Y + CHARACTER_H + 10;
        MAIN_Y = MAIN_LABEL_Y + 14;
        HOTBAR_Y = MAIN_Y + 3 * PITCH + 6;
        BASE_HEIGHT = HOTBAR_Y + CELL + PAD;
        STORAGE_LABEL_Y = BASE_HEIGHT + 4;
        STORAGE_Y = STORAGE_LABEL_Y + 14;
        BUTTON_X = WIDTH - PAD - BUTTON_W;
        BUTTON_Y = MAIN_LABEL_Y - 3;
        TRASH_X = CARD_X + (CARD_W - CELL) / 2;
        TRASH_Y = RESULT_Y;
    }

    /** The size of an item drawn in a cell (two pixels in from each side). */
    static int itemSize() {
        return CELL - 4;
    }

    /** The panel's height with {@code rows} of backpack storage shown. */
    static int height(int rows) {
        return rows <= 0 ? BASE_HEIGHT : STORAGE_Y + rows * PITCH - 2 + PAD;
    }

    /**
     * The cell corner of the inventory menu's slot {@code index} (0 result, 1..4 crafting, 5..8 armour, 9..35 inventory,
     * 36..44 hotbar, 45 off hand, 46 backpack, 47.. storage); null when not shown ({@code crafting} false: the creative
     * inventory has none).
     */
    @Nullable
    static int[] cell(int index, boolean crafting) {
        if (index == InventoryMenu.RESULT_SLOT) return crafting ? new int[]{RESULT_X, RESULT_Y} : null;
        if (index >= InventoryMenu.CRAFT_SLOT_START && index < InventoryMenu.CRAFT_SLOT_END) {
            int i = index - InventoryMenu.CRAFT_SLOT_START;
            return crafting ? new int[]{CRAFT_X + (i % 2) * PITCH, CRAFT_Y + (i / 2) * PITCH} : null;
        }
        if (index >= InventoryMenu.ARMOR_SLOT_START && index < InventoryMenu.ARMOR_SLOT_END) {
            return new int[]{PAD, EQUIP_Y + (index - InventoryMenu.ARMOR_SLOT_START) * PITCH};
        }
        if (index >= InventoryMenu.INV_SLOT_START && index < InventoryMenu.INV_SLOT_END) {
            int i = index - InventoryMenu.INV_SLOT_START;
            return new int[]{PAD + (i % 9) * PITCH, MAIN_Y + (i / 9) * PITCH};
        }
        if (index >= InventoryMenu.USE_ROW_SLOT_START && index < InventoryMenu.USE_ROW_SLOT_END) {
            return new int[]{PAD + (index - InventoryMenu.USE_ROW_SLOT_START) * PITCH, HOTBAR_Y};
        }
        if (index == InventoryMenu.SHIELD_SLOT) return new int[]{SIDE_X, EQUIP_Y + 3 * PITCH};
        if (index == BackpackMenus.BACKPACK_SLOT) return new int[]{SIDE_X, EQUIP_Y};
        if (BackpackMenus.isStorage(index)) {
            int i = index - BackpackMenus.STORAGE_START;
            return new int[]{PAD + (i % 9) * PITCH, STORAGE_Y + (i / 9) * PITCH};
        }
        return null;
    }

    /** Puts a slot's item at the cell {@code cell} (relative to the screen's corner), or out of sight. */
    static void place(Slot slot, @Nullable int[] cell, int dx, int dy) {
        SlotAccess access = (SlotAccess) slot;
        if (cell == null) {
            access.blockland$setX(HIDDEN);
            access.blockland$setY(HIDDEN);
        } else {
            access.blockland$setX(dx + cell[0] + 2);
            access.blockland$setY(dy + cell[1] + 2);
        }
    }

    /** The empty-slot glyph of inventory menu slot {@code index}, or null. */
    @Nullable
    static InventoryStyle.Glyph glyph(int index) {
        return switch (index) {
            case 5 -> InventoryStyle.Glyph.HELMET;
            case 6 -> InventoryStyle.Glyph.CHEST;
            case 7 -> InventoryStyle.Glyph.LEGS;
            case 8 -> InventoryStyle.Glyph.BOOTS;
            case InventoryMenu.SHIELD_SLOT -> InventoryStyle.Glyph.SHIELD;
            case BackpackMenus.BACKPACK_SLOT -> InventoryStyle.Glyph.BACKPACK;
            default -> null;
        };
    }
}
