package com.blockland.backend.shop.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Development payment provider: every charge succeeds immediately. */
@Component
public class MockPaymentProvider implements PaymentProvider {
    private static final Logger LOG = LoggerFactory.getLogger(MockPaymentProvider.class);

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public Result charge(Charge charge) {
        String reference = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LOG.info("[mock payment] {} paid {} {} for {} ({})", charge.playerName(), charge.amountCents() / 100.0,
                charge.currency(), charge.description(), reference);
        return new Result(true, reference, "模拟支付成功");
    }
}
