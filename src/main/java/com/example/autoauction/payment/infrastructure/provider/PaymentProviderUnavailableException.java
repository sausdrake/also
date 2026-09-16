package com.example.autoauction.payment.infrastructure.provider;

/**
 * Fail-closed bootstrap: thrown by {@link UnavailablePaymentProviderPort#initiatePayment} to make
 * "no real bank/ELQR provider is configured" an honest, explicitly-named failure in logs, distinct
 * from a real provider's own timeout/connection-failure exceptions. Deliberately a plain
 * {@code RuntimeException} - never {@link com.example.autoauction.payment.domain.port.PaymentProviderPort.PaymentInitiationRejectedException},
 * which is reserved for an explicit synchronous rejection BY a real provider. This type carries no
 * such information (there is no provider to reject anything), so
 * {@link com.example.autoauction.payment.application.PaymentCreationOrchestrator} correctly treats
 * it as ambiguous and leaves the Payment in {@code CREATED}, per the already-locked three-way
 * initiation-outcome split.
 */
public class PaymentProviderUnavailableException extends RuntimeException {
    public PaymentProviderUnavailableException(String message) {
        super(message);
    }
}
