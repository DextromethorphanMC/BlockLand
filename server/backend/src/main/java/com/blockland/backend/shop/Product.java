package com.blockland.backend.shop;

import java.time.Instant;

/**
 * A shop item. Buying it grants {@link #entitlement} to the player (for {@link #durationDays}, 0 = permanent).
 *
 * @param priceCents   price in the smallest currency unit (fen)
 * @param imageVersion changes whenever the image is replaced (cache key); null when there is no image
 */
public record Product(
        String id,
        String name,
        String description,
        String category,
        int priceCents,
        String currency,
        String entitlement,
        int durationDays,
        boolean enabled,
        int sortOrder,
        String imageVersion,
        Instant updatedAt
) {
    public Product withImageVersion(String version, Instant time) {
        return new Product(id, name, description, category, priceCents, currency, entitlement, durationDays, enabled, sortOrder, version, time);
    }
}
