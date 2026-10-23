package com.blockland.network.packet;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A mode's kit preset for the client's loadout editor. The editor is opened from the lobby, where no registries are
 * synchronised yet, so items travel as {@link ItemView}s: what the editor shows, never the item data itself.
 *
 * @param signature identifies the preset; the client keeps its saved arrangement only while it matches
 */
public record S2CKitData(String modeId, String modeTitle, String signature, List<Entry> entries) {

    /** @param defaultSlot where the admin put the item */
    public record Entry(ItemView item, String defaultSlot) {
    }

    /** An item as the loadout editor shows it: icon, count, glint, durability and the server's tooltip lines. */
    public record ItemView(ResourceLocation id, int count, boolean foil, int damage, List<Component> tooltip) {
        /** A plain stack of the item for drawing (its real data stays on the server). */
        public ItemStack toStack() {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id), Math.max(1, count));
            if (foil) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            if (damage > 0 && stack.isDamageableItem()) stack.setDamageValue(damage);
            return stack;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(id);
            buf.writeVarInt(count);
            buf.writeBoolean(foil);
            buf.writeVarInt(damage);
            buf.writeVarInt(tooltip.size());
            for (Component line : tooltip) buf.writeUtf(TextJson.write(line), 4096);
        }

        static ItemView decode(FriendlyByteBuf buf) {
            ResourceLocation id = buf.readResourceLocation();
            int count = buf.readVarInt();
            boolean foil = buf.readBoolean();
            int damage = buf.readVarInt();
            int lines = Math.min(buf.readVarInt(), 64);
            List<Component> tooltip = new ArrayList<>(lines);
            for (int i = 0; i < lines; i++) tooltip.add(TextJson.read(buf.readUtf(4096)));
            return new ItemView(id, count, foil, damage, tooltip);
        }
    }

    public static void encode(S2CKitData msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.modeId, 64);
        buf.writeUtf(msg.modeTitle);
        buf.writeUtf(msg.signature, 32);
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            entry.item.encode(buf);
            buf.writeUtf(entry.defaultSlot, 16);
        }
    }

    public static S2CKitData decode(FriendlyByteBuf buf) {
        String modeId = buf.readUtf(64);
        String title = buf.readUtf();
        String signature = buf.readUtf(32);
        int size = Math.min(buf.readVarInt(), 64);
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(new Entry(ItemView.decode(buf), buf.readUtf(16)));
        return new S2CKitData(modeId, title, signature, entries);
    }
}
