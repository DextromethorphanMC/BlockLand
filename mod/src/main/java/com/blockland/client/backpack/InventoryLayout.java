package com.blockland.client.backpack;

import com.blockland.backpack.BackpackMenus;
import com.blockland.mixin.inventory.SlotAccess;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;

/**
 * Where the player's inventory goes on the inventory screens (UI spec §1), from the content area's corner; the survival
 * screen and the creative screen's inventory tab share it. Cells are 20 px on a 22 px pitch (the item sits 2 in).
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
    static final int PAD = 14, HEADER = 26, CELL = 20, PITCH = 22;
    /** Width of the inventory's content (9 cells and the padding). */
    static final int WIDTH = PAD * 2 + 9 * PITCH - 2;
    static final int EQUIP_Y = 34;
    static final int CHARACTER_X = PAD + 26, CHARACTER_W = 52, CHARACTER_H = 4 * PITCH - 2;
    static final int SIDE_X = CHARACTER_X + CHARACTER_W + 6;
    static final int CARD_X = SIDE_X + 26, CARD_W = WIDTH - PAD - (SIDE_X + 26);
    static final int CRAFT_X = CARD_X + 4, CRAFT_Y = EQUIP_Y + 18;
    static final int RESULT_X = CARD_X + CARD_W - 4 - CELL, RESULT_Y = CRAFT_Y + 11;
    static final int MAIN_LABEL_Y = EQUIP_Y + CHARACTER_H + 10;
    static final int MAIN_Y = MAIN_LABEL_Y + 14;
    static final int HOTBAR_Y = MAIN_Y + 3 * PITCH + 6;
    static final int BASE_HEIGHT = HOTBAR_Y + CELL + PAD;
    static final int STORAGE_LABEL_Y = BASE_HEIGHT + 4;
    static final int STORAGE_Y = STORAGE_LABEL_Y + 14;
    /** The 打开背包 / 收起背包 button. */
    static final int BUTTON_W = 64, BUTTON_H = 14, BUTTON_X = WIDTH - PAD - BUTTON_W, BUTTON_Y = MAIN_LABEL_Y - 3;
    /** The creative inventory's destroy-item cell, where crafting is in survival. */
    static final int TRASH_X = CARD_X + (CARD_W - CELL) / 2, TRASH_Y = RESULT_Y;
    static final int HIDDEN = -10000;

    private InventoryLayout() {
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
