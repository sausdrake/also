package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.web.dto.AdminLateSuccessPaymentResponse;
import com.example.autoauction.payment.web.dto.AdminStuckInitiationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 26/35: narrowly-scoped, strictly read-only admin observability for {@code Payment} rows -
 * two capabilities (late-success visibility, and stuck-initiation-recovery visibility). Depends
 * only on {@link PaymentRepository} and (Phase 35) {@link PaymentEventRepository} - both
 * read-only ports; has no dependency on and never calls {@code DepositService}, {@code
 * PaymentConfirmationService}, {@code PaymentExpiryService}, {@code PaymentProviderPort}, {@code
 * PaymentCreationService}/{@code PaymentCreationOrchestrator}, or {@code
 * ReconciliationCaseRepository}. Deliberately separate from {@code AdminReconciliationService} -
 * this class knows nothing about {@code ReconciliationCase} and must not grow into a general
 * payment-administration service.
 */
@Service
public class AdminPaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    public AdminPaymentService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminLateSuccessPaymentResponse> listLateSuccesses() {
        return paymentRepository.findAllLateSuccessesNewestFirst().stream()
                .map(AdminLateSuccessPaymentResponse::fromDomain)
                .toList();
    }

    /**
     * Phase 35: read-only visibility into {@code CREATED} payments that have durable {@code
     * INITIATION_ACCEPTED} evidence - classified through the exact same pure {@link
     * InitiationEvidenceClassifier} the real recovery scheduler uses, so this view can never
     * disagree with what recovery would actually do. Never mutates {@code Payment}/{@code
     * PaymentEvent}, never calls {@code PaymentProviderPort}, never creates a {@code
     * ReconciliationCase}.
     */
    @Transactional(readOnly = true)
    public List<AdminStuckInitiationResponse> listStuckInitiations() {
        return paymentRepository.findCreatedWithInitiationEvidence().stream()
                .map(this::toStuckInitiationResponse)
                .toList();
    }

    private AdminStuckInitiationResponse toStuckInitiationResponse(Payment payment) {
        List<PaymentEvent> evidence = paymentEventRepository.findByPaymentIdAndType(
                payment.getId(), PaymentEventType.INITIATION_ACCEPTED);
        InitiationEvidenceClassification classification = InitiationEvidenceClassifier.classify(evidence);
        return AdminStuckInitiationResponse.fromDomain(payment, classification);
    }
}
