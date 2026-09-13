package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;

/**
 * P1.5: the orchestrator's final result for one creation request - the durable {@link Payment}
 * plus, only on the synchronous-accept path, the provider's transient presentation data
 * (checkout URL / QR payload). Deliberately not persisted anywhere (Payment has no such columns -
 * see the P1.5 implementation report's disclosed limitation): an idempotent replay, or any path
 * other than synchronous accept, carries {@code null} for both.
 */
public record PaymentCreationView(Payment payment, String checkoutUrl, String qrPayload) {

    public static PaymentCreationView withoutPresentationData(Payment payment) {
        return new PaymentCreationView(payment, null, null);
    }
}
