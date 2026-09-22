package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseDetailResponse;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseListItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Phase 20/24: admin orchestration for {@link ReconciliationCase} inspection (detail/list) and
 * administrative case closure. {@code resolveCase} performs ONLY the {@code ReconciliationCase}
 * lifecycle transition ({@code OPEN -> RESOLVED} via the domain's own {@code resolve()}) - it is
 * administrative metadata closure, never financial reconciliation. This class has no method that
 * touches {@code DepositService}, {@code PaymentConfirmationService}, or any {@code Payment}/
 * {@code PaymentEvent} mutation; {@code getCaseDetail}/{@code listCases} remain purely read-only
 * as before.
 */
@Service
public class AdminReconciliationService {

    private final ReconciliationCaseRepository reconciliationCaseRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    public AdminReconciliationService(ReconciliationCaseRepository reconciliationCaseRepository,
                                       PaymentRepository paymentRepository,
                                       PaymentEventRepository paymentEventRepository) {
        this.reconciliationCaseRepository = reconciliationCaseRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    @Transactional(readOnly = true)
    public AdminReconciliationCaseDetailResponse getCaseDetail(Long caseId) {
        ReconciliationCase reconciliationCase = reconciliationCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Дело сверки не найдено: " + caseId));

        // A ReconciliationCase with no resolvable Payment is a data-integrity break, not an
        // ordinary "not found" - IllegalStateException (409), distinct from the case-not-found
        // 404 above, so the two failure modes are never confused by the caller.
        Payment payment = paymentRepository.findById(reconciliationCase.getPaymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Платёж " + reconciliationCase.getPaymentId() + ", связанный с делом сверки " + caseId
                                + ", не найден"));

        // F5: query-level filtering (was: load every event for this payment via findByPaymentId,
        // then filter to RECONCILIATION in Java). findByPaymentIdAndType already exists for
        // exactly this purpose and shares findByPaymentId's own ordering (occurredAt ASC, id ASC)
        // - the externally visible evidence list/order is unchanged, only where the filter runs.
        List<PaymentEvent> reconciliationEvidence =
                paymentEventRepository.findByPaymentIdAndType(payment.getId(), PaymentEventType.RECONCILIATION);

        return AdminReconciliationCaseDetailResponse.assemble(reconciliationCase, payment, reconciliationEvidence);
    }

    /**
     * Phase 22: read-only case list, newest-opened first, with optional {@code status}/{@code
     * type} filtering (AND semantics when both given) applied AFTER retrieval - preserving
     * {@link ReconciliationCaseRepository#findAllNewestFirst()}'s ordering, never re-sorting.
     * Enriches each row with its Payment's requested amount/currency via a per-row {@link
     * PaymentRepository#findById}; a missing Payment does not drop the row or fail the request -
     * it is returned with {@code paymentExists=false} and null amount/currency, mirroring {@code
     * AdminUserController.listUsers(withBalance=true)}'s own per-row-resilience precedent. Never
     * loads {@link PaymentEvent} data - the list DTO has no field capable of holding it.
     */
    @Transactional(readOnly = true)
    public List<AdminReconciliationCaseListItemResponse> listCases(ReconciliationCaseStatus status,
                                                                     ReconciliationCaseType type) {
        return reconciliationCaseRepository.findAllNewestFirst().stream()
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> type == null || c.getType() == type)
                .map(c -> paymentRepository.findById(c.getPaymentId())
                        .map(payment -> AdminReconciliationCaseListItemResponse.withPayment(c, payment))
                        .orElseGet(() -> AdminReconciliationCaseListItemResponse.withoutPayment(c)))
                .toList();
    }

    /**
     * Phase 24: administrative closure only - {@code OPEN -> RESOLVED} via {@link
     * ReconciliationCase#resolve}. {@code adminId} and {@code resolvedAt} are always
     * caller-supplied from the authenticated principal / server clock (never client-supplied -
     * enforced by the controller, not by this method). No financial collaborator is referenced:
     * this method does not import or call {@code PaymentRepository}, {@code
     * PaymentEventRepository}, or {@code DepositService}. An already-RESOLVED case's second
     * resolution attempt is rejected by the domain method itself ({@code IllegalStateException},
     * mapped to 409 by the existing {@code GlobalExceptionHandler}) - not swallowed into a
     * silent no-op.
     */
    @Transactional
    public AdminReconciliationCaseDetailResponse.CaseSummary resolveCase(Long caseId, Long adminId, String resolutionNote) {
        ReconciliationCase reconciliationCase = reconciliationCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Дело сверки не найдено: " + caseId));

        reconciliationCase.resolve(adminId, resolutionNote, OffsetDateTime.now());
        ReconciliationCase resolved = reconciliationCaseRepository.save(reconciliationCase);

        return AdminReconciliationCaseDetailResponse.CaseSummary.fromDomain(resolved);
    }
}
