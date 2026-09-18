package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseDetailResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 20: read-only admin orchestration for one {@link ReconciliationCase} - assembles the
 * case, its linked {@link Payment}, and its {@link PaymentEventType#RECONCILIATION} evidence
 * into a single detail view. Deliberately a small, single-purpose read service, not a general
 * reconciliation domain service: it has no method that mutates anything, never calls {@code
 * ReconciliationCase.resolve()}, never saves a case/payment/event, and never touches {@code
 * DepositService} or {@code PaymentConfirmationService}.
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

        List<PaymentEvent> reconciliationEvidence = paymentEventRepository.findByPaymentId(payment.getId()).stream()
                .filter(event -> event.getType() == PaymentEventType.RECONCILIATION)
                .toList();

        return AdminReconciliationCaseDetailResponse.assemble(reconciliationCase, payment, reconciliationEvidence);
    }
}
