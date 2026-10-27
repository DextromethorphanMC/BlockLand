package com.blockland.server.kit;

import com.blockland.common.KitSlots;
import com.blockland.network.packet.S2CKitData;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.KitItemDef;
import com.blockland.server.item.ItemStackFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Starting kits ("开局预设物品"), usable by any game type. The items always come from the server's preset;
 * a player only chooses where they go. Their arrangement is stored by the client and submitted when queueing,
 * and the server merely checks it is a valid rearrangement of the preset (same entries, armour unchanged).
 * <p>
 * Besides the admin's preset a game type may add <i>role items</i> (e.g. 枪战爆破's bomb): every player arranges them
 * like the rest, but only the players the game hands them to get them ({@link #giveRoleItem}).
 */
public final class KitService {
    private final MinecraftServer server;
    private final KitLayoutStore layouts;
    private ItemStackFactory items;
    /** Runs after a kit was handed out (the 工匠 builds are fitted to its weapons). */
    private Consumer<ServerPlayer> afterApply = player -> { };
    /** The role items of a mode (from its game type), after the preset in kit order. */
    private Function<GameModeDef, List<KitItemDef>> roleItems = mode -> List.of();

    public KitService(MinecraftServer server, KitLayoutStore layouts) {
        this.server = server;
        this.layouts = layouts;
    }

    public void setAfterApply(Consumer<ServerPlayer> afterApply) {
        this.afterApply = afterApply;
    }

    public void setRoleItems(Function<GameModeDef, List<KitItemDef>> roleItems) {
        this.roleItems = roleItems;
    }

    /** The preset followed by the type's role items: what a layout arranges. */
    public List<KitItemDef> entries(GameModeDef mode) {
        List<KitItemDef> extra = roleItems.apply(mode);
        if (extra.isEmpty()) return mode.kitOrEmpty();
        List<KitItemDef> all = new ArrayList<>(mode.kitOrEmpty());
        all.addAll(extra);
        return all;
    }

    /** Whether there is anything for a player to arrange (the lobby offers the loadout editor then). */
    public boolean hasEntries(GameModeDef mode) {
        return !entries(mode).isEmpty();
    }

    /** Clears the player's inventory and gives the mode's kit (not its role items) in the player's submitted arrangement. */
    public void apply(ServerPlayer player, GameModeDef mode) {
        player.getInventory().clearContent();
        List<KitItemDef> kit = mode.kitOrEmpty();
        List<String> slots = layoutFor(player.getUUID(), mode);
        for (int i = 0; i < kit.size(); i++) {
            ItemStack stack = createStack(kit.get(i));
            if (!stack.isEmpty()) place(player, slots.get(i), stack);
        }
        afterApply.accept(player);
        player.containerMenu.broadcastChanges();
    }

    /**
     * Gives the player one of the mode's role items ({@code item} = its id as the type declared it) where their layout
     * put it; somewhere free when that slot is taken.
     *
     * @return false when the mode has no such role item or the item does not exist
     */
    public boolean giveRoleItem(ServerPlayer player, GameModeDef mode, String item) {
        List<KitItemDef> entries = entries(mode);
        List<String> slots = layoutFor(player.getUUID(), mode);
        for (int i = mode.kitOrEmpty().size(); i < entries.size(); i++) {
            if (!entries.get(i).item.equals(item)) continue;
            ItemStack stack = createStack(entries.get(i));
            if (stack.isEmpty()) return false;
            place(player, slots.get(i), stack);
            player.containerMenu.broadcastChanges();
            return true;
        }
        return false;
    }

    /** Fits the player's saved builds (工匠) to weapons handed out outside a kit, e.g. a practice mode's guns. */
    public void refit(ServerPlayer player) {
        afterApply.accept(player);
        player.containerMenu.broadcastChanges();
    }

    /** The preset as the client's loadout editor needs it (the client merges its own saved arrangement). */
    public S2CKitData editorData(GameModeDef mode) {
        List<S2CKitData.Entry> entries = new ArrayList<>();
        Item.TooltipContext context = Item.TooltipContext.of(server.registryAccess());
        List<KitItemDef> all = entries(mode);
        int presetSize = mode.kitOrEmpty().size();
        for (int i = 0; i < all.size(); i++) {
            KitItemDef item = all.get(i);
            ItemStack stack = createStack(item);
            if (stack.isEmpty()) continue;
            List<Component> tooltip = new ArrayList<>(stack.getTooltipLines(context, null, TooltipFlag.NORMAL));
            if (i >= presetSize) tooltip.add(Component.translatable("blockland.kit.role_item").withStyle(ChatFormatting.GOLD));
            S2CKitData.ItemView view = new S2CKitData.ItemView(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(),
                    stack.hasFoil(), stack.getDamageValue(), tooltip);
            entries.add(new S2CKitData.Entry(view, KitSlots.normalize(item.slot)));
        }
        return new S2CKitData(mode.id, mode.title == null ? mode.id : mode.title, signature(mode), entries);
    }

    /**
     * Remembers the arrangement a player submitted with their queue request.
     *
     * @param slots empty to use the default layout
     * @return null when accepted (or empty), otherwise why it was ignored
     */
    @Nullable
    public Component submitLayout(UUID player, GameModeDef mode, String signature, List<String> slots) {
        layouts.remove(player, mode.id);
        if (slots.isEmpty() || entries(mode).isEmpty()) return null;
        if (!signature(mode).equals(signature)) return Component.translatable("blockland.kit.error.outdated");
        Component error = KitSlots.validateLayout(defaults(mode), slots);
        if (error != null) return error;
        layouts.save(player, mode.id, new KitLayoutStore.Layout(signature, slots.stream().map(KitSlots::normalize).toList()));
        return null;
    }

    /** Identifies the kit contents (role items included); clients keep a layout only while this stays the same. */
    public String signature(GameModeDef mode) {
        StringBuilder sb = new StringBuilder();
        for (KitItemDef item : entries(mode)) sb.append(item.item).append('*').append(item.count).append('@').append(item.slot).append(';');
        return Integer.toHexString(sb.toString().hashCode());
    }

    private List<String> layoutFor(UUID player, GameModeDef mode) {
        return layouts.load(player, mode.id)
                .filter(layout -> signature(mode).equals(layout.signature()))
                .filter(layout -> KitSlots.validateLayout(defaults(mode), layout.slots()) == null)
                .map(KitLayoutStore.Layout::slots)
                .orElseGet(() -> defaults(mode));
    }

    private List<String> defaults(GameModeDef mode) {
        return entries(mode).stream().map(k -> KitSlots.normalize(k.slot)).toList();
    }

    private ItemStack createStack(KitItemDef def) {
        if (items == null) items = new ItemStackFactory(server.registryAccess());
        return items.create(def.item, def.count).orElse(ItemStack.EMPTY);
    }

    private static void place(ServerPlayer player, String slot, ItemStack stack) {
        String s = KitSlots.normalize(slot);
        EquipmentSlot equipment = switch (s) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case KitSlots.OFFHAND -> EquipmentSlot.OFFHAND;
            case KitSlots.BACKPACK -> com.blockland.backpack.Backpacks.SLOT;
            default -> null;
        };
        if (equipment != null && player.getItemBySlot(equipment).isEmpty()) {
            player.setItemSlot(equipment, stack);
            return;
        }
        int index = KitSlots.mainIndex(s);
        if (index >= 0 && player.getInventory().getItem(index).isEmpty()) {
            player.getInventory().setItem(index, stack);
        } else {
            player.getInventory().add(stack);
        }
    }
}
