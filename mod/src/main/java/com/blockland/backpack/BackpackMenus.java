package com.blockland.backpack;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import javax.annotation.Nullable;

/**
 * The backpack's part of the player's own inventory menu (UI spec §2): vanilla's {@link InventoryMenu} gets, after its 46
 * slots, the backpack slot ({@link #BACKPACK_SLOT}: the worn backpack) and {@link BackpackData#maxSlots()} storage slots
 * (the worn backpack's contents, as many usable as it has room for). It stays the one menu vanilla keeps open and
 * synchronises, so moving items is vanilla's, validated by the server. {@code com.blockland.mixin.inventory} only forwards
 * here. Whether the storage is shown is the menu's state, set by a menu button ({@link #BUTTON_EXPAND} /
 * {@link #BUTTON_COLLAPSE}, vanilla's container-button packet): a shift-click from the inventory goes into the backpack
 * only while it is.
 */
public final class BackpackMenus {
    public static final int BACKPACK_SLOT = InventoryMenu.SHIELD_SLOT + 1;
    public static final int STORAGE_START = BACKPACK_SLOT + 1;
    /** Menu button ids (above anything vanilla's own menus use). */
    public static final int BUTTON_COLLAPSE = 7300, BUTTON_EXPAND = 7301;

    /** Slot positions in the inventory screen (pixels from its corner, as vanilla's own slots). */
    public static final int BACKPACK_X = 77, BACKPACK_Y = 44;
    public static final int STORAGE_X = 8, STORAGE_Y = 178;

    /**
     * The inventory menu with the backpack's state, and its protected slot methods (implemented on vanilla's
     * {@link InventoryMenu} by a mixin).
     */
    public interface Host {
        boolean blockland$backpackOpen();

        void blockland$setBackpackOpen(boolean open);

        /** {@code addSlot}. */
        Slot blockland$addSlot(Slot slot);

        /** {@code moveItemStackTo}. */
        boolean blockland$move(ItemStack stack, int from, int to, boolean backwards);
    }

    /** What the client's screen shows: the slots are inactive (not drawn, not clicked) anywhere else (the creative tab). */
    public interface View {
        View NONE = new View() {
        };

        default boolean backpackSlotShown() {
            return false;
        }

        default boolean storageShown() {
            return false;
        }
    }

    private static volatile View view = View.NONE;

    private BackpackMenus() {
    }

    /** The client module's view of the screen (null = none). */
    public static void setView(@Nullable View shown) {
        view = shown == null ? View.NONE : shown;
    }

    public static int storageSlots() {
        return BackpackData.maxSlots();
    }

    public static boolean isStorage(int index) {
        return index >= STORAGE_START && index < STORAGE_START + storageSlots();
    }

    /** Adds the backpack slot and the storage slots (called once at the end of the inventory menu's constructor). */
    public static void addSlots(Player owner, Host menu) {
        menu.blockland$addSlot(new BackpackSlot(new WornBackpack(owner), BACKPACK_X, BACKPACK_Y));
        Storage storage = new Storage(owner, storageSlots());
        for (int i = 0; i < storageSlots(); i++) {
            menu.blockland$addSlot(new StorageSlot(storage, i, STORAGE_X + (i % 9) * 18, STORAGE_Y + (i / 9) * 18));
        }
    }

    /** Menu button: opening or closing the backpack's storage. Returns whether {@code id} was one of these. */
    public static boolean button(Host menu, int id) {
        if (id != BUTTON_EXPAND && id != BUTTON_COLLAPSE) return false;
        menu.blockland$setBackpackOpen(id == BUTTON_EXPAND);
        return true;
    }

    /**
     * A creative player sets one of the menu's slots by number (vanilla's creative packet takes only 1..45): returns
     * whether {@code index} is the backpack slot or the storage — then the packet is handled here, the stack set when it
     * may go there.
     */
    public static boolean creativeSet(ServerPlayer player, int index, ItemStack stack) {
        if (index != BACKPACK_SLOT && !isStorage(index)) return false;
        if (!player.hasInfiniteMaterials() || index >= player.inventoryMenu.slots.size()) return true;
        if (!stack.isEmpty() && (stack.getCount() > stack.getMaxStackSize() || !stack.isItemEnabled(player.level().enabledFeatures()))) {
            return true;
        }
        Slot slot = player.inventoryMenu.getSlot(index);
        if (!stack.isEmpty() && !slot.mayPlace(stack)) return true;
        slot.setByPlayer(stack);
        player.inventoryMenu.setRemoteSlot(index, stack);
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    /** Whether a double-click may collect from {@code slot}: not from a backpack that is closed. */
    public static boolean collects(Host menu, Slot slot) {
        return !(slot instanceof StorageSlot) || menu.blockland$backpackOpen();
    }

    /**
     * A shift-click on {@code index}: the backpack's own moves (out of the backpack slot or the storage; a backpack onto the
     * back; into the open backpack), else null and vanilla's quick move goes on.
     */
    @Nullable
    public static ItemStack quickMove(InventoryMenu menu, Player player, int index) {
        if (!(menu instanceof Host host)) return null;
        boolean open = host.blockland$backpackOpen();
        if (index < 0 || index >= menu.slots.size()) return null;
        Slot slot = menu.slots.get(index);
        if (!slot.hasItem()) return null;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        int storageEnd = STORAGE_START + Math.min(storageSlots(), Backpacks.capacity(Backpacks.worn(player)));
        boolean moved;
        if (index == BACKPACK_SLOT || isStorage(index)) {
            moved = host.blockland$move(stack, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END, index != BACKPACK_SLOT);
        } else if (index >= InventoryMenu.INV_SLOT_START && index <= InventoryMenu.SHIELD_SLOT) {
            if (Backpacks.isBackpack(stack) && !menu.slots.get(BACKPACK_SLOT).hasItem()) {
                moved = host.blockland$move(stack, BACKPACK_SLOT, BACKPACK_SLOT + 1, false);
            } else if (open && storageEnd > STORAGE_START && Backpacks.fitsInside(stack)) {
                moved = host.blockland$move(stack, STORAGE_START, storageEnd, false);
                if (!moved) return null;
            } else {
                return null;
            }
        } else {
            return null;
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY, before);
        else slot.setChanged();
        if (stack.getCount() == before.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return before;
    }

    // ---- the slots ----------------------------------------------------------------------------------

    /** The backpack slot: only backpacks, one at a time. */
    static final class BackpackSlot extends Slot {
        BackpackSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return Backpacks.isBackpack(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean isActive() {
            return view.backpackSlotShown();
        }
    }

    /** A storage slot: usable while the worn backpack has room for it; never a backpack. */
    static final class StorageSlot extends Slot {
        private final Storage storage;

        StorageSlot(Storage storage, int index, int x, int y) {
            super(storage, index, x, y);
            this.storage = storage;
        }

        boolean usable() {
            return getContainerSlot() < storage.capacity();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return usable() && Backpacks.fitsInside(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return usable();
        }

        @Override
        public boolean isActive() {
            return usable() && view.storageShown();
        }
    }

    /** The body's backpack slot as a one-slot container. */
    static final class WornBackpack implements Container {
        private final Player owner;

        WornBackpack(Player owner) {
            this.owner = owner;
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return owner.getItemBySlot(Backpacks.SLOT).isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return owner.getItemBySlot(Backpacks.SLOT);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            ItemStack stack = owner.getItemBySlot(Backpacks.SLOT);
            if (stack.isEmpty() || count <= 0) return ItemStack.EMPTY;
            ItemStack taken = stack.split(count);
            if (stack.isEmpty()) owner.setItemSlot(Backpacks.SLOT, ItemStack.EMPTY);
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack stack = owner.getItemBySlot(Backpacks.SLOT);
            owner.setItemSlot(Backpacks.SLOT, ItemStack.EMPTY);
            return stack;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            owner.setItemSlot(Backpacks.SLOT, stack);
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            owner.setItemSlot(Backpacks.SLOT, ItemStack.EMPTY);
        }
    }

    /**
     * The worn backpack's contents as a container: a live list read from the stack whenever the worn stack (or what is
     * stored on it) changed, written back on every change, so slots that grow or shrink the stacks they return (vanilla's
     * clicks do) are kept.
     */
    static final class Storage implements Container {
        private final Player owner;
        private final NonNullList<ItemStack> items;
        @Nullable
        private ItemStack readFrom;
        @Nullable
        private ItemContainerContents readContents;

        Storage(Player owner, int size) {
            this.owner = owner;
            this.items = NonNullList.withSize(size, ItemStack.EMPTY);
        }

        private ItemStack backpack() {
            return Backpacks.worn(owner);
        }

        int capacity() {
            return Math.min(items.size(), Backpacks.capacity(backpack()));
        }

        /** The list, read again when the worn backpack or its stored contents changed since. */
        private NonNullList<ItemStack> items() {
            ItemStack backpack = backpack();
            ItemContainerContents contents = backpack.isEmpty() ? null : backpack.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if (backpack != readFrom || contents != readContents) {
                if (backpack.isEmpty()) {
                    for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
                } else {
                    Backpacks.read(backpack, items);
                }
                readFrom = backpack;
                readContents = contents;
            }
            return items;
        }

        private void write() {
            ItemStack backpack = backpack();
            if (backpack.isEmpty()) return;
            readContents = Backpacks.write(backpack, items);
            readFrom = backpack;
        }

        @Override
        public int getContainerSize() {
            return items.size();
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : items()) if (!stack.isEmpty()) return false;
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot < capacity() ? items().get(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            if (slot >= capacity()) return ItemStack.EMPTY;
            ItemStack taken = ContainerHelper.removeItem(items(), slot, count);
            if (!taken.isEmpty()) write();
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            if (slot >= capacity()) return ItemStack.EMPTY;
            ItemStack taken = ContainerHelper.takeItem(items(), slot);
            write();
            return taken;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot >= capacity()) return;
            items().set(slot, stack);
            write();
        }

        @Override
        public void setChanged() {
            if (capacity() > 0) {
                items();
                write();
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
            write();
        }
    }
}
