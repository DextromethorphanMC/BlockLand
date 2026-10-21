package com.blockland.network.packet;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A mode's kit preset for the client's loadout editor.
 *
 * @param signature identifies the preset; the client keeps its saved arrangement only while it matches
 */
public record S2CKitData(String modeId, String modeTitle, String signature, List<Entry> entries) {

    /** @param defaultSlot where the admin put the item */
    public record Entry(ItemStack stack, String defaultSlot) {
    }

    public static void encode(S2CKitData msg, RegistryFriendlyByteBuf buf) {
        buf.writeUtf(msg.modeId, 64);
        buf.writeUtf(msg.modeTitle);
        buf.writeUtf(msg.signature, 32);
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, entry.stack);
            buf.writeUtf(entry.defaultSlot, 16);
        }
    }

    public static S2CKitData decode(RegistryFriendlyByteBuf buf) {
        String modeId = buf.readUtf(64);
        String title = buf.readUtf();
        String signature = buf.readUtf(32);
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(new Entry(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readUtf(16)));
        return new S2CKitData(modeId, title, signature, entries);
    }
}
