package com.example.autoauction.payment.domain.port;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Slice 2: the ONLY outbound payment-provider dependency the production code is allowed to have.
 * Provider-agnostic by construction - no signature, nonce, bank code, terminal id or
 * provider-specific status code appears in this interface. A concrete adapter for a real,
 * business-selected provider is Phase 3B and does not exist yet; the application layer depends
 * only on this port.
 */
public interface PaymentProviderPort {

    /**
     * Asks the provider to create a payment for the given server-owned merchant reference and
     * amount, and returns a provider reference plus optional, provider-neutral presentation data
     * (a checkout URL and/or a QR payload) for the client to complete the payment.
     */
    PaymentInitiationResult initiatePayment(PaymentInitiationCommand command);

    /**
     * Server-side authoritative status pull for a previously-initiated payment. The confirmation
     * path credits on the value returned here, never on a client- or webhook-supplied figure.
     */
    ProviderPaymentStatus getPaymentStatus(String providerReference);

    /**
     * Provider-neutral request to create a payment.
     *
     * @param merchantReference server-generated, unique, opaque; carries no target-account data
     * @param amount            requested amount (KGS, scale 2)
     * @param currency          always {@code "KGS"} in this MVP
     * @param returnUrl         where the provider should send the user back after the flow
     */
    record PaymentInitiationCommand(String merchantReference, BigDecimal amount, String currency, String returnUrl) {
    }

    /**
     * Provider-neutral result of {@link #initiatePayment}.
     *
     * @param providerReference the provider's handle for this payment (used later for status pulls)
     * @param checkoutUrl       a redirect/checkout URL, if the provider uses one
     * @param qrPayload         a QR string/payload, if the provider uses one
     */
    record PaymentInitiationResult(String providerReference, Optional<String> checkoutUrl, Optional<String> qrPayload) {
    }

    /**
     * P1.5: thrown by a provider adapter ONLY when the provider has definitively, synchronously
     * rejected the initiation request itself (e.g. a validation error the provider's own
     * synchronous response carried) - distinct from, and never to be conflated with, any other
     * failure (timeout, connection failure, unrecognised response), which the caller must treat
     * as ambiguous (never as a rejection) per the locked P1.5 architecture. A minimal, necessary,
     * provider-neutral addition: without it there is no way to distinguish an explicit rejection
     * from ambiguity at this boundary, which would make the locked "provider rejected -> FAILED"
     * rule unimplementable without inventing provider-specific behaviour.
     */
    class PaymentInitiationRejectedException extends RuntimeException {
        public PaymentInitiationRejectedException(String message) {
            super(message);
        }
    }

    /** Provider-neutral, normalised payment state. */
    enum ProviderPaymentState {
        PENDING,
        SUCCEEDED,
        FAILED,
        /** The provider could not be reached or returned an unrecognised state. */
        UNKNOWN
    }

    /**
     * Provider-neutral, normalised status.
     *
     * @param state                 normalised state
     * @param confirmedAmount       the amount the provider actually settled (null unless SUCCEEDED)
     * @param currency              the settled currency (null unless SUCCEEDED)
     * @param providerTransactionId the provider's globally-unique transaction id (null unless SUCCEEDED)
     */
    record ProviderPaymentStatus(ProviderPaymentState state, BigDecimal confirmedAmount, String currency,
                                 String providerTransactionId) {
    }
}
