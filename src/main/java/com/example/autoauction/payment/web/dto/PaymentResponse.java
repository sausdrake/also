package com.example.autoauction.payment.web.dto;

import com.example.autoauction.payment.application.PaymentCreationView;
import com.example.autoauction.payment.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * P1.5: provider-neutral response contract. Deliberately excludes {@code providerTransactionId},
 * {@code providerReference}, any raw provider payload, and every internal persistence detail -
 * only {@code checkoutUrl}/{@code qrPayload} (already provider-neutral fields on
 * {@link com.example.autoauction.payment.domain.port.PaymentProviderPort.PaymentInitiationResult})
 * are ever surfaced, and only transiently (see {@link PaymentCreationView}'s own Javadoc for why
 * an idempotent replay cannot reproduce them).
 */
@Schema(description = "Платёж (ответ)")
public record PaymentResponse(
        Long id,
        String merchantReference,
        BigDecimal amountRequested,
        String currency,
        String status,
        OffsetDateTime expiresAt,
        String checkoutUrl,
        String qrPayload
) {

    public static PaymentResponse from(PaymentCreationView view) {
        Payment p = view.payment();
        return new PaymentResponse(
                p.getId(),
                p.getMerchantReference(),
                p.getAmountRequested(),
                p.getCurrency(),
                p.getStatus().name(),
                p.getExpiresAt(),
                view.checkoutUrl(),
                view.qrPayload());
    }
}
