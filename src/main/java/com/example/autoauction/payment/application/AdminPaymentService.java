package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.web.dto.AdminLateSuccessPaymentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 26: narrowly-scoped, strictly read-only admin observability for {@code Payment} rows -
 * currently exactly one capability (late-success visibility). Depends only on {@link
 * PaymentRepository}; has no dependency on and never calls {@code DepositService}, {@code
 * PaymentConfirmationService}, {@code PaymentExpiryService}, {@code PaymentEventRepository}, or
 * {@code ReconciliationCaseRepository}. Deliberately separate from {@code
 * AdminReconciliationService} - this class knows nothing about {@code ReconciliationCase} and
 * must not grow into a general payment-administration service.
 */
@Service
public class AdminPaymentService {

    private final PaymentRepository paymentRepository;

    public AdminPaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminLateSuccessPaymentResponse> listLateSuccesses() {
        return paymentRepository.findAllLateSuccessesNewestFirst().stream()
                .map(AdminLateSuccessPaymentResponse::fromDomain)
                .toList();
    }
}
