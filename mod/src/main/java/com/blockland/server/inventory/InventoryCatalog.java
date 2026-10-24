package com.blockland.server.inventory;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the things a player can own (库存) are. Ownership is a backend entitlement — a key such as {@code graffiti:smile}
 * with an end date ({@code null} = permanent; admins grant with days, 0 = permanent) — and every module that offers
 * ownable things registers an {@link ItemType} for its key prefix here at start-up (graffiti, name cards, …). The
 * inventory tab, equipping into {@link com.blockland.server.profile.Cosmetics} slots and the admin's item list are built
 * from these types alone, so a new kind of item needs no change to the inventory.
 */
public final class InventoryCatalog {
    /**
     * How one owned item is shown.
     *
     * @param icon   what the client draws: a texture ({@code blockland:textures/…png}), {@code card:<id>} (a name card,
     *               drawn by the client's card renderer) or {@code graffiti:<id>}; empty = a plain tile
     * @param rarity {@code common}, {@code rare}, {@code epic}, {@code legendary} (colour only)
     */
    public record Display(Component name, Component description, String icon, String rarity) {
    }

    /** A kind of ownable item. */
    public interface ItemType {
        /** The key prefix, with its colon: {@code graffiti:}. */
        String prefix();

        /** Inventory category (its translation key, e.g. {@code blockland.inventory.category.graffiti}). */
        String categoryKey();

        /** How the item with this key is shown; null when the key names nothing this server knows (still listed, plainly). */
        @Nullable
        Display display(String key);

        /** Every key of this type the server knows (for the admin's item list), in display order. */
        List<String> keys();

        /** Keys every player owns without an entitlement (defaults), shown as permanent. */
        default List<String> freeKeys() {
            return List.of();
        }

        /** The {@code Cosmetics} slots an item of this type can be put in (empty = nothing to equip). */
        default List<String> slots() {
            return List.of();
        }
    }

    private static final List<ItemType> types = new ArrayList<>();

    private InventoryCatalog() {
    }

    public static synchronized void register(ItemType type) {
        types.removeIf(t -> t.prefix().equals(type.prefix()));
        types.add(type);
    }

    public static synchronized List<ItemType> types() {
        return Collections.unmodifiableList(new ArrayList<>(types));
    }

    /** The type a key belongs to, or null. */
    @Nullable
    public static synchronized ItemType typeOf(String key) {
        for (ItemType type : types) {
            if (key.startsWith(type.prefix())) return type;
        }
        return null;
    }
}
