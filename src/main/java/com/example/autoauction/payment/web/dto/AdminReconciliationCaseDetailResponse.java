package com.example.autoauction.payment.web.dto;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
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
                CaseSummary.fromDomain(reconciliationCase, recurredAfterResolution(reconciliationCase, reconciliationEvidence)),
                PaymentSummary.fromDomain(payment),
                reconciliationEvidence.stream().map(EvidenceEntry::fromDomain).toList());
    }

    /**
     * F3: read-only signal - whether at least one RECONCILIATION observation occurred strictly
     * after the case's {@code resolvedAt}. {@code null} for a non-RESOLVED case ("recurred after
     * resolution" is meaningless before any resolution happened) - never {@code false} there, so
     * a client can distinguish "not yet resolved" from "resolved, nothing recurred since".
     * {@code reconciliationEvidence} is already ordered {@code occurredAt ASC, id ASC} (see
     * {@code PaymentEventRepository#findByPaymentIdAndType}), so its last element is
     * deterministically the latest observation - no separate sort/max needed. An observation
     * occurring at EXACTLY {@code resolvedAt} is treated as NOT recurred (strict "after", not "at
     * or after") - it could be the very evidence an admin reviewed when resolving, and counting a
     * timestamp tie as recurrence would be a false positive.
     */
    private static Boolean recurredAfterResolution(ReconciliationCase reconciliationCase,
                                                     List<PaymentEvent> reconciliationEvidence) {
        if (reconciliationCase.getStatus() != ReconciliationCaseStatus.RESOLVED) {
            return null;
        }
        if (reconciliationEvidence.isEmpty()) {
            return false;
        }
        PaymentEvent latest = reconciliationEvidence.get(reconciliationEvidence.size() - 1);
        return latest.getOccurredAt().isAfter(reconciliationCase.getResolvedAt());
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
            OffsetDateTime resolvedAt,
            Boolean recurredAfterResolution
    ) {
        /**
         * Used where no RECONCILIATION-evidence context is available (e.g. the resolve-action
         * response, which deliberately never reads PaymentEventRepository - see {@code
         * AdminReconciliationService#resolveCase}). Always {@code null} for this overload; use
         * {@link #fromDomain(ReconciliationCase, Boolean)} where the signal is actually computed.
         */
        public static CaseSummary fromDomain(ReconciliationCase c) {
            return fromDomain(c, null);
        }

        public static CaseSummary fromDomain(ReconciliationCase c, Boolean recurredAfterResolution) {
            return new CaseSummary(
                    c.getId(),
                    c.getPaymentId(),
                    c.getType().name(),
                    c.getStatus().name(),
                    c.getOpenedAt(),
                    c.getResolvedByAdminId(),
                    c.getResolutionNote(),
                    c.getResolvedAt(),
                    recurredAfterResolution);
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
