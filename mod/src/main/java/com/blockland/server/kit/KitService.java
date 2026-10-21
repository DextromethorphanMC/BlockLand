package com.blockland.server.kit;

import com.blockland.common.KitSlots;
import com.blockland.network.packet.S2CKitData;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.KitItemDef;
import com.blockland.server.item.ItemStackFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Starting kits ("开局预设物品"), usable by any game type. The items always come from the server's preset;
 * a player only chooses where they go. Their arrangement is stored by the client and submitted when queueing,
 * and the server merely checks it is a valid rearrangement of the preset (same entries, armour unchanged).
 */
public final class KitService {
    private final MinecraftServer server;
    private final KitLayoutStore layouts;
    private ItemStackFactory items;

    public KitService(MinecraftServer server, KitLayoutStore layouts) {
        this.server = server;
        this.layouts = layouts;
    }

    /** Clears the player's inventory and gives the mode's kit in the player's submitted arrangement. */
    public void apply(ServerPlayer player, GameModeDef mode) {
        player.getInventory().clearContent();
        List<KitItemDef> kit = mode.kitOrEmpty();
        List<String> slots = layoutFor(player.getUUID(), mode);
        for (int i = 0; i < kit.size(); i++) {
            ItemStack stack = createStack(kit.get(i));
            if (!stack.isEmpty()) place(player, slots.get(i), stack);
        }
        player.containerMenu.broadcastChanges();
    }

    /** The preset as the client's loadout editor needs it (the client merges its own saved arrangement). */
    public S2CKitData editorData(GameModeDef mode) {
        List<S2CKitData.Entry> entries = new ArrayList<>();
        for (KitItemDef item : mode.kitOrEmpty()) {
            entries.add(new S2CKitData.Entry(createStack(item), KitSlots.normalize(item.slot)));
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
    public String submitLayout(ServerPlayer player, GameModeDef mode, String signature, List<String> slots) {
        layouts.remove(player.getUUID(), mode.id);
        if (slots.isEmpty() || mode.kitOrEmpty().isEmpty()) return null;
        if (!signature(mode).equals(signature)) return "开局装备已更新，本次使用默认布局";
        String error = KitSlots.validateLayout(defaults(mode), slots);
        if (error != null) return error;
        layouts.save(player.getUUID(), mode.id, new KitLayoutStore.Layout(signature, slots.stream().map(KitSlots::normalize).toList()));
        return null;
    }

    /** Identifies the kit contents; clients keep a layout only while this stays the same. */
    public static String signature(GameModeDef mode) {
        StringBuilder sb = new StringBuilder();
        for (KitItemDef item : mode.kitOrEmpty()) sb.append(item.item).append('*').append(item.count).append('@').append(item.slot).append(';');
        return Integer.toHexString(sb.toString().hashCode());
    }

    private List<String> layoutFor(UUID player, GameModeDef mode) {
        return layouts.load(player, mode.id)
                .filter(layout -> signature(mode).equals(layout.signature()))
                .filter(layout -> KitSlots.validateLayout(defaults(mode), layout.slots()) == null)
                .map(KitLayoutStore.Layout::slots)
                .orElseGet(() -> defaults(mode));
    }

    private static List<String> defaults(GameModeDef mode) {
        return mode.kitOrEmpty().stream().map(k -> KitSlots.normalize(k.slot)).toList();
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
