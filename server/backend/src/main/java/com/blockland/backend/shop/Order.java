package com.blockland.backend.shop;

import java.time.Instant;

/** One purchase attempt and its payment outcome. */
public record Order(
        String id,
        String playerUuid,
        String playerName,
        String productId,
        String productName,
        int priceCents,
        String currency,
        Status status,
        String paymentProvider,
        String paymentReference,
        String message,
        Instant createdAt
) {
    public enum Status { PAID, FAILED }
}
