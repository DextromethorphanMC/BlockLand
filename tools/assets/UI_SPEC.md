# BlockLand UI contract (inventory and backpacks)

## 1. The inventory screens (`client/backpack`)

BlockLand draws its own survival and creative inventories (`ClientBackpack` swaps them in on `ScreenEvent.Opening`
for vanilla's `InventoryScreen` and `CreativeModeInventoryScreen`; vanilla's own redirects between the two as the game
mode changes keep working). Both are vanilla's container screens underneath — `ModernInventoryScreen` over the player's
`InventoryMenu`, `BlockLandCreativeScreen` a subclass of vanilla's creative screen — so every click, drag-split, number
key, double-click collect, shift-click, Q, middle-click clone, shift-click delete, saved hotbar and search behaves as in
vanilla and the server checks what reaches it. BlockLand only places the slots and draws everything.

* **No vanilla GUI sprite.** Panels, cells, hover rings, buttons, the scrollbar, the tab rail, the search field, the
  tooltips, the effect list and the empty-slot hints (vector glyphs: helmet, chestplate, leggings, boots, shield,
  backpack, bin, magnifier, arrow) are BlockLand's (`InventoryStyle`); counts, durability and cooldowns of stacks are
  drawn in BlockLand's font and greys (`SlotPainter`, which follows vanilla's slot rules for splits, drag previews and
  the carried stack). Item icons, tab icons (their items) and effect icons are content and keep their colours.
* **Black and white only.** Every colour is a grey of one brightness, the one setting: the bar in the header
  (`ClientSettings.inventoryBrightness`, near black … light grey; text flips to dark past the middle).
* **Motion.** Hover states, the buttons and the backpack's storage ease with `anim/Spring`; the panel grows and shrinks
  as the storage opens.
* **Layout** (`InventoryLayout`, 20 px cells on a 22 px pitch, 14 px padding; the slot positions are the screens', set
  through `mixin/inventory/SlotAccess` — the server's menu never reads them):
  * header: the title (the creative screen: the tab's name, or the search field on the search tab) and the brightness bar;
    held down on its free space and dragged, the panel moves (`PanelDrag`: an offset from the centred place, shared by
    both screens for the session, kept inside the window; the creative rail and the recipe book beside it move along);
  * a left column of armour, the character in its own card, the backpack slot and the off hand beside it;
  * crafting in its own card on the right (2×2 grid → result); the creative inventory tab has the destroy-item slot
    there instead;
  * 物品栏 with the 打开背包 / 收起背包 button on its row, the 9 × 3 inventory, a divider, the 9-slot hotbar;
  * the worn backpack's storage opening under the hotbar (its name, how full it is, its rows).
* **Creative** (`BlockLandCreativeScreen`): a rail of tabs on the left — search pinned at the top, the inventory at the
  bottom, every other tab (all of Forge's pages, BlockLand's and vanilla's) scrolling between them with the wheel; item
  tabs show the 9 × 7 grid (`BlockLandCreativeScreen.GRID_ROWS`; vanilla's picker and its shared container are patched
  to that many rows by `mixin/inventory/ItemPickerMenuMixin` / `CreativeGridMixin`) with BlockLand's scrollbar (dragged,
  or the wheel anywhere else) and the hotbar, how many items the tab has beside the hotbar's label. Vanilla's own tab and scroller areas are switched off in the subclass (`checkTabClicked`,
  `insideScrollbar`); its private tab selection, search box, scroll position and destroy slot are reached through
  `mixin/inventory/CreativeScreenAccess`, the container screen's drag state through `ContainerScreenAccess`. The search
  box stays vanilla's (focus, typing, search) under the drawn field.
* Potion effects as cards beside the panel.
* **Recipe book** (`RecipeBookPanel`, survival only — vanilla's creative screen has none): vanilla's crafting recipe book on
  vanilla's own data — the player's `ClientRecipeBook` and its `RecipeCollection`s (selected by what fits the 2×2 grid,
  craftable by the inventory and grid contents, refreshed when the inventory changes), the session's recipe search tree,
  placing through `gameMode.handlePlaceRecipe` (shift: as many as possible; an uncraftable recipe once), the ghost recipe the
  server answers with (the screen is a `RecipeUpdateListener`), the open / "craftable only" settings kept in the book and sent
  with `ServerboundRecipeBookChangeSettingsPacket`, new recipes highlighted until seen — behaving as vanilla's
  `RecipeBookComponent` / `RecipeBookPage` / `RecipeButton` / `OverlayRecipeComponent` / `GhostSlots`, drawn by BlockLand. A
  配方书 button beside the title opens a card left of the inventory (the two centred together, the inventory moving right as
  vanilla's does; on a screen too narrow for both it opens over the inventory, which is then neither drawn nor touched, Esc
  closes it): the title and the 仅可合成 / 全部 filter, vanilla's five category tabs (search, equipment, building, misc,
  redstone; empty ones hidden, a new recipe makes its tab bob), the search field (T focuses it, as vanilla's chat key does),
  a 5-column grid of recipes (pages turned with the arrows or the wheel; a dimmed cell cannot be crafted, a corner mark holds
  several recipes, the result cycling), a right click opening the alternatives as mini 3×3 grids. Ghost ingredients are
  drawn faded in the grid, the result framed; their tooltips show on empty slots; Enter places the last recipe again.

## 2. Backpacks (`com.blockland.backpack`)

* Data: `data/blockland/backpack/backpacks.json` — `id`, `rows` of 9 (1..6), `depth` (pixels of the model on the back).
  Read from the mod's own files at start-up (the entries are registered items), the same on both sides. Ships
  `backpack_small` (1 row), `backpack_medium` (2), `backpack_large` (3). Art: `tools/assets/gen_backpack_items.py`
  (vanilla-style icons, the back model's textures, item models).
* **Where it is worn:** the body's `EquipmentSlot.BODY` slot (`Backpacks.SLOT`), which players never use otherwise. Vanilla
  saves it with the player, syncs it to other players (the model on the back), drops it on death and clears it with the
  inventory (kits, `PlayerUtil.reset`); `BackpackContent` copies it to the respawned player when the inventory is kept.
  Kits can put one there: kit slot `backpack` (`KitSlots.BACKPACK`, fixed like armour).
* **What it holds** lives on the stack (`minecraft:container`), so it travels with the item. No backpacks in backpacks,
  nor anything that refuses to go in a container item.
* **The menu:** `com.blockland.mixin.inventory.InventoryMenuMixin` adds to vanilla's `InventoryMenu` (forwarding to
  `BackpackMenus`): slot 46 = the backpack slot (backpacks only), slots 47.. = the storage (as many as the largest
  backpack has; those beyond the worn one's capacity unusable). Both are inactive (not drawn, not clicked) outside
  BlockLand's inventory screens. Creative players set slots by number and vanilla's server takes only 1..45:
  `CreativeSlotPacketMixin` hands 46.. to `BackpackMenus.creativeSet` (the same rules as the slots). Whether the storage is open is the
  menu's state, set through vanilla's container-button packet (`BackpackMenus.BUTTON_EXPAND` / `BUTTON_COLLAPSE`): only
  then does a shift-click from the inventory go into the backpack, and a double-click collect take from it. Shift-click
  on a backpack puts it on the back when the slot is free; out of the backpack slot or the storage goes to the inventory.
* Used from the hand a backpack goes on the back (swapping with the one worn).
* On the back: `client/backpack/BackpackLayer`, a vanilla-style cuboid model (pack, lid, pocket, shoulder straps) in the
  body part's frame through vanilla's entity render types.
