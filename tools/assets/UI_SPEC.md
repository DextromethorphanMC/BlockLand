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
* **Layout** (`InventoryLayout`, 20 px cells on a 22 px pitch at the natural size — the grip changes the cell size —, 14 px padding; the slot positions are the screens', set
  through `mixin/inventory/SlotAccess` — the server's menu never reads them):
  * header: the title (the creative screen: the tab's name, or the search field on the search tab) and the brightness bar;
    held down on its free space and dragged, the panel moves (`PanelDrag`: an offset from the centred place, shared by
    both screens for the session, kept inside the window; the creative rail and the recipe book beside it move along);
    the grip in the panel's bottom-right corner resizes it — its width and height, never a zoom — its top-left corner
    staying put: the grip picks the cell size whose panel corner comes nearest the mouse (`ClientSettings.inventoryCell`,
    18–40 px, never larger than the window) and `InventoryLayout.setCell` lays everything out for it (cells, the
    character and crafting cards, the grids); items fill their cells (counts and bars keep their size, in the item's
    corner), labels, buttons and the header keep their size, and vanilla's slot hit-test asks about the item's size;
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

## 3. The HUD (`client/hud/layout` and its elements)

Every movable piece of the HUD is a `HudElement` (id, name, its box now, a default place, whether it has something to
show, and a `render(g, delta, x, y, editing)` that draws it at any place) registered with `HudLayout`. The layout knows
nothing else about the elements; modules register their own at start-up.

* **Places.** An element is held by one of nine `HudAnchor`s of the screen (corners, edge middles, centre): its own same
  point keeps a fixed offset (GUI pixels) from the screen's, so it stays in its corner or centred at any window size or
  GUI scale, always pulled back inside the screen. Dropped in the editor, it takes the anchor of the third of the screen
  its centre is in. Places, turned-off elements and the vanilla switch are `ClientSettings.hud` (`HudSettings`).
* **Drawing.** Two overlay layers draw every enabled, visible element at its place: one above vanilla's hotbar layer,
  one late, above the chat's (`HudElement.late()`). F1 hides them; while the editor is open they stay quiet (it draws
  the elements itself, with `editing` true: a sample where there is nothing to show).
* **Choices.** An element may offer `HudElement.Option`s (a key, a name, its values; the first is the default) — the
  hotbar's 横 / 竖. The editor shows a button cycling each for the selected element, the HUD tab a choice row under the
  element's switch; the values are `HudSettings.options` (`HudLayout.option` / `setOption`).
* **Sizes.** An element may be resizable (`HudElement.resize()`: its default box, its smallest and largest, whether
  it keeps square — the compass). Its `width()` / `height()` return `HudLayout.size(this)`: the size the player gave
  it (`HudSettings.sizes`, kept within its limits), else its default; `HudLayout.resize` sets it, `resetElement` puts
  place and size back. Its contents follow the box it is given.
* **The editor.** 设置 → HUD (§3.1).

### 3.1 The HUD editor

* **The tab** (设置 → HUD, `SettingsScreen.hudRows`): 编辑 HUD 布局 (the page's main action; only in a world — the
  editor draws the live HUD), BlockLand 绘制原版 HUD (`HudSettings.replaceVanilla`), one on/off row per registered
  element (by its `nameKey`) with, for a resizable one, 宽度 and 高度 sliders under it (one 大小 when it keeps square),
  the minimap's size (48–192 px), range (32–256 blocks) and rotation, and 全部复位 (every place and size, every element
  back on, the vanilla switch on, the minimap's defaults).
* **The editor** (`HudEditorScreen`, over the live game with a light dim and faint centre lines; `HudLayout.setEditing`
  while open): every registered element drawn at its place with `editing` true (a sample where it has nothing to show;
  greyed with 已隐藏 when turned off), outlined — white under the mouse, accent when selected — with its name on a tag
  over it. A small toolbar above the crosshair: 完成, 全部复位, 吸附 on/off, 边距 0/4 px, the selected element's name
  (with its size when resizable) with 复位 and 隐藏/显示, and the controls in one line; it hides while dragging.
* **Moving.** Left-drag moves an element (`HudLayout.move`: the anchor of the third of the screen its centre ends in).
  The arrow keys nudge the selected one by 1 px (Shift: 10), a right click puts it back at its default place and size
  (`HudLayout.resetElement`, as 复位). Esc or 完成 saves (`ClientSettings.save`) and returns to the settings page.
* **Resizing.** A resizable element shows a grip in its bottom-right corner when selected (fainter under the mouse;
  white while hovered or held). Dragged, its right and bottom edges follow the mouse, snapping to the same lines as a
  moved box's edges (Alt: freely), within its limits (a square one takes the side the mouse pulled further) and inside
  the screen; its top-left corner stays put and its anchor follows (`HudLayout.move`). Its size shows by the corner
  while held. Ctrl + the arrow keys resize the selected one by 1 px (Shift: 10).
* **Snapping** (on unless turned off or Alt is held): each axis on its own, the dragged box's start, middle or end snaps
  to the nearest line within 5 GUI px — the screen's edges at the margin (`HudSettings.snapMargin`, 0 or 4 px), the
  screen's centre line, and every other element's start, middle and end. The line it snapped to is drawn across the
  screen (centre lines blue, edges and margins accent, other elements gold). The box always stays inside the screen.
* **BlockLand's own panels** are elements too: 枪械弹药 (`GunHud.Panel`, the ammo counter: bottom right, 14 px in),
  装备 (`EquipmentHud.Panel`) and 刀具 (`KnifeHud.Panel`), both at the same bottom-right spot (only one is held at a
  time), and 护甲插板 (`ArmorHud.Plates`, over vanilla's hearts). What stays round the crosshair — the crosshair, hit
  marker, hints, the fuse and call-outs, a plate breaking or going in — is still drawn by each module's own layer.

### 3.2 Health, food, armour, hotbar (`client/hud/vanilla`)

`VanillaHud` registers six elements in BlockLand's look (dark rounded tracks with a hairline rim, flat fills with a
lighter top row, pixel glyphs drawn from tiny bitmaps in any colour — `BarIcons` —, numbers through `Draw`/`UiFont`):

| Element | What it shows | Default place (anchor `BOTTOM`) |
|---|---|---|
| `hotbar` | nine 20 px cells on a 22 px pitch, the selected one lit and outlined (the highlight springs between cells); items with vanilla's pick-up pop and decorations (count, durability, cooldown); the off hand's cell on its side (only when it holds something), the attack indicator (set to 来自物品栏) on the other side as a cell filling up; its choice 横 / 竖 lays it out down instead (20 × 248: the cells top to bottom, the off hand and the indicator at the ends) | centred, 2 px off the bottom (248 × 20: the cells are 196 wide, a 26 px column each side) |
| `experience` | the experience bar (3 px) with the level in green over it, its own element (横 / 竖 like the hotbar: down, it fills upwards under the level) | right over the hotbar's cells, where vanilla has it (196 × 10) |
| `health` | a heart, health over max health (absorption after it in gold, its own `+n` by the number); green poisoned, ash withering, ice blue frozen; a hit flashes the bar and leaves the lost part fading behind (held 12 ticks, then running down); regeneration runs a sheen along it, healing glows round it, low health (≤ 4 or a fifth) pulses red and shakes the heart; hardcore hearts have eyes | above the hotbar, left half (96 × 11) |
| `food` | the number, a bar filling from the drumstick on the right, saturation as a gold layer over it; green with the hunger effect; the drumstick shakes at 6 or less. Above it, only while the air runs short, a thin air bar with a bubble (red flashing while drowning) | above the hotbar, right half (96 × 18) |
| `armor` | a breastplate, armour points out of 20, the number; toughness as a thin blue line under the bar; only while armour is worn | over the health bar |
| `item_name` | a newly selected item's name in its rarity's colour (italic when renamed) on a dark plate, for vanilla's notification time, then fading — its own element so it can follow a moved hotbar | over the armour row, centred |

Health, food and armour show only when the game mode can hurt the player; the food bar steps aside while vanilla shows a
ridden animal's health in its place. In the editor each draws a sample when it would be hidden (12 armour, a diamond
sword's name, the off hand's empty cell, the air bar dimmed). The health trail and the item-name timer are kept tick by
tick in `VanillaHud` (vanilla's Gui keeps its own privately).

While `HudSettings.replaceVanilla` is on, vanilla's drawing of these stays off through `mixin/hud` (forwarders only, they
ask `VanillaHud.replaces()`): `Gui.renderItemHotbar`, `renderPlayerHealth` (hearts, armour, food, air),
`renderSelectedItemName(GuiGraphics, int)` cancelled at their head, the experience level redirected out of
`renderHotbarAndDecorations`, `ExperienceBarRenderer.renderBackground` cancelled. The spectator bar, a ridden animal's
health, the locator bar and the horse's jump bar stay vanilla's, where vanilla puts them. Off: vanilla draws everything
as before and these elements are not drawn.

### 3.3 Chat (`client/hud/chat`)

The chat is the element `chat` (late; by default where vanilla's is: bottom left, the page ending 40 pixels over the
bottom). Its box is the whole open chat — the page at the focused height and the queued-message line under it — at
the chat scale. Only while the HUD's vanilla switch is on (`HudLayout.replacesVanilla`) does BlockLand take part:
vanilla's `chat_overlay` layer stands down, and `mixin/chat` forwards to `ChatHud`; switched off, vanilla's chat is
untouched (the messages are wrapped again either way when the switch or the font changes).

* **Vanilla keeps the chat.** Messages, the history, the chat delay queue, deletions and their markers, scrolling (the
  wheel, page up / down, shift for single lines), "new messages while scrolled up", the chat screen and its input are
  vanilla's. Every chat option applies: scale, width, focused / unfocused height, text and background opacity, line
  spacing, colours, hidden (nothing drawn, nothing under the mouse). Lines fade as vanilla's: full for 9 s, gone at 10 s,
  all shown while the chat screen is open.
* **BlockLand's layout.** Messages are wrapped with `UiFont`'s metrics to the chat's width (colour codes stripped when
  chat colours are off; lines after a message's first indented by a space) — `ChatComponent`'s wrapped lines are these,
  so vanilla's scrolling and line counts hold. The closed chat draws each line on its own fading row; the open chat one
  panel behind the page with a hairline over it. A message's tag shows as vanilla's: the indicator strip on its rows'
  left, the tag's icon after the hovered message. The scrollbar beside the page turns amber when something new arrived
  while scrolled up; the "N messages waiting" line sits under the page.
* **What is under the mouse.** The chat screen asks `ChatComponent` for the style (hover and click events), the message
  tag (its tooltip) and the queued-message line at the mouse; `mixin/chat` answers from BlockLand's layout: lines by
  the page's bottom and the line height, characters by `UiFont` widths. The chat screen still draws the chat through
  `ChatComponent.render(…, true)`, which draws the open chat at the element's place; the closed chat is the element on
  the HUD layer.
* **Editor sample**: three lines on the open chat's panel.

### 3.4 Minimap (`client/hud/minimap`)

* **Element** `minimap` (`MinimapElement`): a framed square `MinimapSettings.size` GUI pixels across showing
  `range` blocks; default top left (the top right holds vanilla's effect icons, the kill feed and the 大逃杀 map). With
  `rotate` the map turns so the player's way is up and north (北 / N) rides the rim; otherwise north is up and the
  player's arrow turns. The player is an outlined arrow in the middle; allies (`isAlliedTo`: team mates) are dots,
  pinned to the rim when off the map — nobody else is ever shown. The player's block coordinates sit under it. Hidden
  with the F3 screen and while another module's map shows (`Minimap.hideWhile`: the 大逃杀 HUD during flight and
  fight). In the editor without a world: the frame and a placeholder.
* **Picture** (`MinimapCache`, `MinimapGrid`, `ColumnSampler`): a 512 × 512 texture that wraps — world column (x, z)
  lives at texel (x & 511, z & 511) — drawn as one textured quad turned through the GUI pose and clipped to the square
  (the texture repeats and filters linearly). Each client tick a pass walks the columns round the player in square
  rings, nearest first, and samples what is new, due again (near the player every 2 s, further every 10 s, the rest
  every 30 s, each column a few ticks apart so rings never come due together) or whose chunk was missing; a texel
  taken over by a new column is cleared at once. Limits per tick: 16 000 columns visited, 2 048 sampled, 0.5 ms; an
  idle pass rests 10 ticks; only changed 64 × 64 tiles go to the GPU (Blaze3D's encoder). Only loaded chunks are read.
* **Colours.** Surface: the top block's map colour (glass and the like skipped), lit by the slope against its north
  and west neighbours; water by depth, a shallow bed showing through. With a roof over the player (a ceiling
  dimension, or ground well over their head at their spot and at most of four spots six blocks round — not a house)
  the layer at their height instead, in 4-block steps: the floor under them darker the deeper it lies, walls at head
  height dark, nothing within 24 blocks near black. Another level, another layer or a jump of more than 128 blocks
  starts the picture afresh.
* Costs show on the F3 timings as `bl/hud/minimap_tick` and `bl/hud/minimap`.

### 3.5 Damage direction (`client/hud/damage`, `server/combat/DamageDirections`)

* **The server decides.** `DamageDirections` follows each hurt of a player through Forge's two events: in the hurt
  event the armour soaks its share (`BlockLandServer` wires the armour in and tells it how much — neither knows the
  other), a monitor after every listener takes what got through (0 when cancelled), and the damage event's last
  listener (lowest priority — EventBus 7.0.1 never runs a bus's monitors while it has no ordinary listener, and the
  damage event has none) what comes off the health (plus the absorption hearts vanilla took just before). A hurt that took something —
  health or plates — sends the victim `DamagePackets.S2CDamage`: where it came from, the attacker to follow (entity
  id, 0 for a point), the health and the plates' share. Where: the damage's own position when it has one, the blast
  (the grenade or shell) for an explosion, else the causing entity's eyes, else the direct entity. A hurt the game's
  rules or a vehicle's hull turn away (cancelled before vanilla's hurt) sends nothing, nor does one with nowhere to
  point at (falling, drowning, burning). Every gun, blade and blast hit counts: they reset the hurt cooldown.
* **Element** `damage_direction` (`DamageDirectionElement`, registered by `DamageIndicator.init`): centred on the
  crosshair, a square box (144 px at first, resizable 64–280) the ring of arrows is fitted into. Shown only while there
  are arrows; in the editor without any, three samples (ahead left, behind, a plate hit from above right).
* **Arrows.** One per attacker (hits with no attacker merge within `mergeDistance` blocks); at most `maxArrows`, the
  oldest dropped. Each frame its bearing (0 ahead, + right) is taken on the level against the camera's yaw, and its
  height is the attacker's elevation above or below the eyes on the level — never from where the camera looks, so
  looking up or down turns no arrow over — to the attacker's eyes (followed while it exists, then its last place) or
  the point. It lies on a level ring of radius 1
  seen by a virtual camera from behind and above (`cameraTilt`, `cameraDistance`, `fov`, perspective): ahead is the
  ring's far side, higher and smaller, behind the near side, lower and larger, sideways foreshortened. The attacker's
  height tips the arrow about its tangent (up to `maxTilt`) and lifts it (`lift`); the tip is held back so the arrow's
  face is always seen at least `minFacing` from edge-on (an arrow ahead may sink less than it may rise, one behind the
  other way round). Shape: a band along the ring
  (`arc` long by strength, `thickness`, to points at both ends, a soft `feather` edge, brighter at its outer rim), a
  notched arrowhead pointing out at the attacker (`headLength`, `headWidth`), a glow under it (`glowSpread`,
  `glowAlpha`). The quads are projected on the CPU and drawn as one GUI element with vanilla's coloured GUI pipeline
  (`QuadMesh`, each quad turned to the GUI's winding so a face tipped away still shows).
* **Feedback.** A hit pops the arrow (`popScale` × the hit's size, settling with a wobble: `popDamping`,
  `popFrequency`), punches it towards the middle (`punch`, deepest at `punchTime`) and flashes it white-hot (`hot`,
  cooling over `flash`). Strength is the recent damage (halving every `halfLife` s) against `fullDamage`, at least
  `minStrength`: longer, thicker arrows. Colour: `health` red, `plates` blue by the plates' share. It stays `hold`
  s after its last hit, then fades over `fade` s. A new player (respawn, another world), death or spectating clears
  them. Look and timing: `assets/blockland/hud/damage_indicator.json`; animation by `RenderClock`.

### 3.6 Compass (`client/hud/compass`)

* **Element** `compass` (`CompassElement`, registered by `Compass.init`): a strip at the top middle, 4 px down,
  resizable from 80 × 12 to 480 × 48 (200 × 22 at first). Hidden with the F3 screen. In the editor without a world: a
  fixed sample heading with two team-mates.
* **What it shows.** A dark band (fading out at both ends, a hairline under it) of the horizon round the
  way the camera faces (`Camera.getYRot()` this frame, so a vehicle's free look and the third-person views count):
  a tick every 5°, a longer one every 15° with its bearing, the eight points by name (北 东北 东 … / N NE E …; the four
  cardinals larger, 北 gold). Bearings are a compass's: north 0°, east 90° (Minecraft's yaw + 180). A caret on the band's
  lower edge marks the middle; under it a plate with the bearing (only when the strip leaves room under the band).
  Ticks and labels sit at sub-pixel places, so the strip slides smoothly, and fade out towards both ends.
* **Team-mates** (`isAlliedTo`, never anyone else): a small outlined triangle in the team's colour (blue without one)
  under the band at their bearing from the camera; out of view, held smaller at that end.
* **Sizes.** Everything is drawn for a 22 px strip and scaled by the height: tick lengths, text, and the pixels a degree
  takes (2 px at 22 px tall: 200 px show 100°). The width only decides how much of the horizon shows — never more than
  300° (a degree then takes more pixels).

## 4. Daily check-in (每日签到, `client/screen/lobby/CheckInPanel` + `server/checkin` + the backend's `checkin`)

The lobby's daily check-in. The backend keeps the streaks and decides; the game server relays (clients never talk to
the backend); the lobby only shows.

* **Rules** (backend `CheckInConfig`, edited on the admin page 签到): a cycle of 1–31 days (7 by default), each day a
  name (blank: the reward item's own name in the player's language, or just 第 N 天), an optional reward — an
  entitlement key, an inventory item (`graffiti:skull`) or a permission (`PRIORITY_QUEUE`) — and how long it lasts
  (0 = permanent; a timed grant extends one the player has). A day begins at midnight in the config's zone
  (`Asia/Shanghai` by default). Checking in on consecutive days walks the cycle and starts it again after its last day
  (the streak keeps counting); a missed day breaks the streak to 0 and the next check-in is the cycle's day 1 again.
  Once per day: asking again the same day answers `already` and changes nothing. Off (`enabled` false): the cycle shows
  but nobody can check in. The default cycle grants graffiti and name cards that exist in the mod's inventory registry.
* **Backend** (`/api/server/checkin/{uuid}` state, `POST …/{uuid}/claim`; admin `/api/admin/checkin/config` GET/PUT,
  `/stats`, `DELETE /players/{uuid}` forgets a player's record — granted rewards stay). Records in
  `checkin-records.json`, the config in `checkin-config.json` (the defaults until saved). A reward is granted with
  `PlayerService.grant` (source `checkin:<day>`), the same way a shop purchase is.
* **Game server** (`server/checkin/CheckInService`, `CheckInPackets`, by player id — it serves the lobby): answers the
  lobby's request with the cycle (reward items carry their name, icon and rarity from `InventoryCatalog`), relays a
  check-in (one at a time per player), confirms it with a toast (第 N 天奖励：…) and fetches the player's status again so
  the reward reaches everything owning items unlocks. The backend unreachable: an error state with a retry button.
* **Lobby** (`TopBar` 签到 button with a breathing red dot while today's check-in waits; `CheckInPanel` over the whole
  lobby; `client/checkin/ClientCheckIn` holds the state and asks again when a new day begins): the title and rules,
  three chips (连续签到, 最长连续, 累计签到), the cycle as cards (up to seven a row — more columns when many rows would not
  fit — each with 第 N 天 / 今天, the reward's icon or a gift box, its name and 永久 / N 天, the rarity's colour along the
  top; checked-in days dimmed under a tilted 已签 stamp; today's card lifted, glowing and outlined in the accent while
  it waits; the cycle's last day marked 大奖), and one button: 签到领取, else 今日已签到 with the countdown to the next
  day, 签到暂未开放, or 重试. A check-in that went through plays on its card: the stamp slams in, the card pops, sparks
  burst, a flash rings out, the streak counts up, 获得 … rises over the card, and the level-up chime plays. Escape, the
  cross or a click outside closes it; while open it takes all input.
