package com.example.autoauction.admin.application;

import com.example.autoauction.admin.web.dto.AdminOperationalStatusResponse;
import com.example.autoauction.payment.application.AdminReconciliationService;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 73 (G-N4): small, read-only assembly service for the admin operational-status endpoint.
 * Reads the in-memory {@link SchedulerHealthSignal} and the already-existing {@link
 * AdminReconciliationService#listCases} (reused as-is, no parallel query/model) - never mutates
 * a payment, never resolves a reconciliation case, never triggers a scheduler, never retries or
 * repairs anything. A stuck-payment count was explicitly NOT added here: no authoritative
 * definition of "stuck" exists in current code (confirmed by direct investigation - {@code
 * ReconciliationCaseType.STUCK} is a documented-but-never-instantiated enum value, and {@code
 * PaymentRepository.findNonTerminalOlderThan} has zero production callers) - inventing a
 * threshold was explicitly out of scope for this phase; see the Phase 73 implementation report.
 */
@Service
public class OperationalStatusService {

    private final SchedulerHealthSignal schedulerHealthSignal;
    private final AdminReconciliationService adminReconciliationService;

    public OperationalStatusService(SchedulerHealthSignal schedulerHealthSignal,
                                     AdminReconciliationService adminReconciliationService) {
        this.schedulerHealthSignal = schedulerHealthSignal;
        this.adminReconciliationService = adminReconciliationService;
    }

    @Transactional(readOnly = true)
    public AdminOperationalStatusResponse getStatus() {
        int openReconciliationCaseCount = adminReconciliationService
                .listCases(ReconciliationCaseStatus.OPEN, null)
                .size();
        return AdminOperationalStatusResponse.assemble(schedulerHealthSignal.snapshot(), openReconciliationCaseCount);
    }
}
