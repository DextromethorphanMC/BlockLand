package com.blockland.server.profile;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * What a player has put on — their name card, their graffiti — as named slots holding inventory item keys
 * ({@code card} → {@code card:aurora}, {@code graffiti.1} … {@code graffiti.4} → {@code graffiti:smile}). Stored by the
 * backend with the player's account (other players see the choice), changed only through the inventory, which checks
 * the player owns the item. Modules that show or use a choice read it here; the profile module installs the store.
 */
public final class Cosmetics {
    public interface Store {
        Store NONE = new Store() {
            @Nullable @Override public String get(UUID player, String slot) { return null; }
            @Override public Map<String, String> all(UUID player) { return Map.of(); }
        };

        /** The item key in a slot, or null when nothing is chosen (the slot's default then applies). */
        @Nullable
        String get(UUID player, String slot);

        /** Every chosen slot of the player. */
        Map<String, String> all(UUID player);
    }

    /** The name card slot. */
    public static final String CARD = "card";

    private static volatile Store store = Store.NONE;

    private Cosmetics() {
    }

    public static void setStore(@Nullable Store next) {
        store = next == null ? Store.NONE : next;
    }

    public static Store get() {
        return store;
    }
}
