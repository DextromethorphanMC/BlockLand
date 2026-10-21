package com.blockland.backend.shop.payment;

/**
 * Charges a player for an order. The shop only depends on this port; a real provider
 * (WeChat Pay, Alipay, ...) replaces {@link MockPaymentProvider} without touching shop logic.
 */
public interface PaymentProvider {

    String id();

    Result charge(Charge charge);

    record Charge(String orderId, String playerUuid, String playerName, String description, int amountCents, String currency) {
    }

    /** @param reference the provider's transaction id */
    record Result(boolean success, String reference, String message) {
    }
}
