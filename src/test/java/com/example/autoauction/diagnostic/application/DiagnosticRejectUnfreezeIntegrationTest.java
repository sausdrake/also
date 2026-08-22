// diagnostic/application/DiagnosticRejectUnfreezeIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 2: proves against a real (H2, test-profile) database that rejecting a completed
 * diagnostic report transitions the seller commitment FROZEN -> AWAITING_FINALIZATION and
 * deliberately does NOT release the applicant's frozen deposit - a Mockito unit test can only
 * prove the service *called* markAwaitingFinalization, not that the SellerDiagnosticCommitment
 * row genuinely persists with the correct status and the ledger/balance stay frozen afterward.
 * Originally named for R-1.2 ("rejection releases the deposit"), which PHASE 2 supersedes: the
 * actual release/charge now happens only in a future finalization phase once the outcome
 * (B or C) is computed. Also proves the diagnostic-request freeze is correctly persisted in
 * seller_diagnostic_commitment and never touches deposit_frozen at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("PHASE 2: интеграционный тест перехода commitment при отклонении диагностического отчёта")
class DiagnosticRejectUnfreezeIntegrationTest {

    @Autowired
    private DiagnosticRequestService diagnosticRequestService;

    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;

    @Autowired
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    @DisplayName("create -> freeze -> reject: commitment переходит в AWAITING_FINALIZATION, депозит " +
            "остаётся замороженным, UNBLOCK-транзакция НЕ создаётся, FrozenDeposit никогда не затрагивался")
    void createFreezeReject_TransitionsCommitmentAndKeepsDepositFrozen() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User applicant = new User(null, "r12applicant", "r12applicant@test.com", "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(applicant).getId();

        depositService.createDeposit(userId, new BigDecimal("50000"));
        BigDecimal availableBeforeFreeze = depositService.getDepositByUserId(userId).availableBalance();

        BigDecimal depositAmount = new BigDecimal("10000");

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("WBSDE91070CZ" + System.nanoTime() % 100000);
        request.setDepositFrozen(depositAmount);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = diagnosticRequestRepository.save(request);
        Long requestId = saved.getId();

        BigDecimal feeAmount = new BigDecimal("4000");
        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, depositAmount, feeAmount);

        // Sanity check the freeze actually happened before testing the reject/unfreeze, AND
        // that it went through the PHASE 1 seller-commitment table, not deposit_frozen.
        assertThat(depositService.getDepositByUserId(userId).availableBalance())
                .isEqualByComparingTo(availableBeforeFreeze.subtract(depositAmount));
        assertThat(sellerDiagnosticCommitmentRepository.existsByDiagnosticRequestId(requestId))
                .as("a seller diagnostic commitment record must exist immediately after freezing")
                .isTrue();
        Long depositIdForFreezeCheck = depositRepository.findByUserId(userId).orElseThrow().getId();
        assertThat(depositRepository.isFrozenForAuction(depositIdForFreezeCheck, requestId))
                .as("the auction-side FrozenDeposit table must never be touched by a diagnostic-request freeze")
                .isFalse();

        // Move the request to COMPLETED (the only status rejectReport accepts) directly via
        // the repository, mirroring how the integration test suite elsewhere sets up
        // mid-lifecycle state without driving the full assignment/submission workflow.
        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        diagnosticRequestService.rejectReport(requestId, 999L, "Недостаточно фотографий", DiagnosticRejectionCategory.VEHICLE_CONDITION);

        // 1) PHASE 2: deposit remains frozen - rejectReport no longer unfreezes it.
        assertThat(depositService.getDepositByUserId(userId).availableBalance())
                .as("blockedAmount must remain at its post-freeze value - release is deferred to finalization")
                .isEqualByComparingTo(availableBeforeFreeze.subtract(depositAmount));

        // 2) No UNBLOCK transaction created.
        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(userId);
        boolean hasUnblock = transactions.stream()
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK
                        && t.getAmount().compareTo(depositAmount) == 0);
        assertThat(hasUnblock).as("no UNBLOCK DepositTransaction should exist - rejectReport never releases in PHASE 2").isFalse();

        // 3) SellerDiagnosticCommitment persists (never deleted) in AWAITING_FINALIZATION, with
        // the diagnosticFeeAmount snapshot intact; the auction-side FrozenDeposit table remains
        // untouched throughout.
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        var commitment = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId);
        assertThat(commitment)
                .as("the seller diagnostic commitment must still exist after rejection")
                .isPresent();
        assertThat(commitment.orElseThrow().getStatus())
                .as("the commitment must have transitioned to AWAITING_FINALIZATION")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitment.orElseThrow().getDiagnosticFeeAmount())
                .as("diagnosticFeeAmount must remain the exact snapshot taken at freeze time")
                .isEqualByComparingTo(feeAmount);
        assertThat(depositRepository.isFrozenForAuction(depositId, requestId))
                .as("no FrozenDeposit row should exist for this request - it was never created here")
                .isFalse();

        // 4) Report status actually persisted as REJECTED, with the rejection category recorded.
        DiagnosticRequest reloaded = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
        assertThat(reloaded.getRejectionCategory()).isEqualTo(DiagnosticRejectionCategory.VEHICLE_CONDITION);
    }
}
