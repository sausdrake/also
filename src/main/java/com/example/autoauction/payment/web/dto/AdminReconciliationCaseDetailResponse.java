package com.example.autoauction.payment.web.dto;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.ReconciliationCase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Phase 20: admin-only, read-only reconciliation case detail. Deliberately excludes {@code
 * ReconciliationCase.version} - persistence metadata, never presentation data (Phase 19 §2).
 * {@code CaseSummary}/{@code PaymentSummary}/{@code EvidenceEntry} mirror exactly the fields
 * Phase 19's audit confirmed are actually backed by existing domain data - no invented field.
 */
@Schema(description = "Дело сверки платежа (детально, только для администратора)")
public record AdminReconciliationCaseDetailResponse(
        CaseSummary reconciliationCase,
        PaymentSummary payment,
        List<EvidenceEntry> evidence
) {

    public static AdminReconciliationCaseDetailResponse assemble(
            ReconciliationCase reconciliationCase, Payment payment, List<PaymentEvent> reconciliationEvidence) {
        return new AdminReconciliationCaseDetailResponse(
                CaseSummary.fromDomain(reconciliationCase),
                PaymentSummary.fromDomain(payment),
                reconciliationEvidence.stream().map(EvidenceEntry::fromDomain).toList());
    }

    @Schema(description = "Метаданные дела сверки")
    public record CaseSummary(
            Long id,
            Long paymentId,
            String type,
            String status,
            OffsetDateTime openedAt,
            Long resolvedByAdminId,
            String resolutionNote,
            OffsetDateTime resolvedAt
    ) {
        public static CaseSummary fromDomain(ReconciliationCase c) {
            return new CaseSummary(
                    c.getId(),
                    c.getPaymentId(),
                    c.getType().name(),
                    c.getStatus().name(),
                    c.getOpenedAt(),
                    c.getResolvedByAdminId(),
                    c.getResolutionNote(),
                    c.getResolvedAt());
        }
    }

    @Schema(description = "Связанный платёж")
    public record PaymentSummary(
            Long id,
            BigDecimal amountRequested,
            String currency,
            String status,
            String providerReference,
            String providerTransactionId,
            BigDecimal amountConfirmed,
            boolean lateSuccessAfterExpiry,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime expiresAt
    ) {
        public static PaymentSummary fromDomain(Payment p) {
            return new PaymentSummary(
                    p.getId(),
                    p.getAmountRequested(),
                    p.getCurrency(),
                    p.getStatus().name(),
                    p.getProviderReference(),
                    p.getProviderTransactionId(),
                    p.getAmountConfirmed(),
                    p.isLateSuccessAfterExpiry(),
                    p.getCreatedAt(),
                    p.getUpdatedAt(),
                    p.getExpiresAt());
        }
    }

    @Schema(description = "Наблюдение поставщика платежей (запись RECONCILIATION)")
    public record EvidenceEntry(
            Long id,
            String source,
            OffsetDateTime occurredAt,
            String rawPayload
    ) {
        public static EvidenceEntry fromDomain(PaymentEvent e) {
            return new EvidenceEntry(e.getId(), e.getSource().name(), e.getOccurredAt(), e.getRawPayload());
        }
    }
}
