package com.example.autoauction.payment.web.dto;

import com.example.autoauction.payment.application.InitiationEvidenceClassification;
import com.example.autoauction.payment.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Phase 35: admin-only, read-only observability row for a {@code CREATED} payment that has
 * durable {@code INITIATION_ACCEPTED} evidence - i.e. a provider-initiation response the
 * application received but never finished persisting as {@code PENDING}. Purely diagnostic - this
 * DTO exists so an admin can inspect and understand the condition, never to correct or re-process
 * anything. {@code classification} is produced by the exact same {@code
 * InitiationEvidenceClassifier} the real recovery path uses, so this view can never disagree with
 * what the scheduler would actually do. Deliberately excludes any raw provider payload beyond
 * {@code malformedReason} (itself a diagnostic message, not the unlabeled raw payload) - the same
 * admin-only exposure level already established for {@code
 * AdminReconciliationCaseDetailResponse.EvidenceEntry.rawPayload}.
 */
@Schema(description = "Платёж CREATED с застрявшим восстановлением инициации (только для администратора)")
public record AdminStuckInitiationResponse(
        Long id,
        Long userId,
        String merchantReference,
        BigDecimal amountRequested,
        String currency,
        OffsetDateTime createdAt,
        String classification,
        List<String> providerReferences,
        String malformedReason
) {

    public static AdminStuckInitiationResponse fromDomain(Payment payment, InitiationEvidenceClassification classification) {
        String classificationName;
        List<String> providerReferences;
        String malformedReason;

        if (classification instanceof InitiationEvidenceClassification.Valid valid) {
            classificationName = "VALID";
            providerReferences = List.of(valid.providerReference());
            malformedReason = null;
        } else if (classification instanceof InitiationEvidenceClassification.Conflicting conflicting) {
            classificationName = "CONFLICTING";
            providerReferences = conflicting.providerReferences();
            malformedReason = null;
        } else if (classification instanceof InitiationEvidenceClassification.Malformed malformed) {
            classificationName = "MALFORMED";
            providerReferences = List.of();
            malformedReason = malformed.reason();
        } else {
            classificationName = "NONE";
            providerReferences = List.of();
            malformedReason = null;
        }

        return new AdminStuckInitiationResponse(
                payment.getId(),
                payment.getUserId(),
                payment.getMerchantReference(),
                payment.getAmountRequested(),
                payment.getCurrency(),
                payment.getCreatedAt(),
                classificationName,
                providerReferences,
                malformedReason);
    }
}
