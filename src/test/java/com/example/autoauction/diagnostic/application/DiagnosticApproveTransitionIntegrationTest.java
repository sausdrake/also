// diagnostic/application/DiagnosticApproveTransitionIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
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
 * PHASE 2: proves against a real (H2, test-profile) database that approveReport transitions the
 * seller commitment FROZEN -> AWAITING_FINALIZATION and does NOT release the applicant's frozen
 * deposit, mirroring DiagnosticRejectUnfreezeIntegrationTest for the approve path. Added
 * specifically because a Mockito-only unit test cannot catch the real bug this test guards
 * against: DiagnosticRequestService.approveReport()/rejectReport() do a plain
 * DiagnosticRequestRepository.save() (an unflushed merge) immediately before calling
 * SellerDiagnosticCommitmentService.markAwaitingFinalization(), whose underlying @Modifying
 * bulk UPDATE only auto-flushes entities on ITS OWN table (seller_diagnostic_commitment) before
 * executing - the DiagnosticRequest status change is a different table/entity, so without
 * flushAutomatically=true on that query, clearAutomatically=true would silently discard the
 * still-unflushed DiagnosticRequest status write when it clears the persistence context. This
 * was observed for real during PHASE 2 verification via the analogous reject-path test and
 * fixed at the query definition (see SpringDataSellerDiagnosticCommitmentJpaRepository);
 * this test proves the same fix covers the approve path too.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("PHASE 2: интеграционный тест перехода commitment при одобрении диагностического отчёта")
class DiagnosticApproveTransitionIntegrationTest {

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
    @DisplayName("create -> freeze -> approve: commitment переходит в AWAITING_FINALIZATION, депозит " +
            "остаётся замороженным, UNBLOCK-транзакция НЕ создаётся, статус заявки реально " +
            "персистится как APPROVED (не теряется при clearAutomatically-очистке)")
    void createFreezeApprove_TransitionsCommitmentAndKeepsDepositFrozen() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User applicant = new User(null, "approveuser" + System.nanoTime(), "approve" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(applicant).getId();

        depositService.createDeposit(userId, new BigDecimal("50000"));
        BigDecimal availableBeforeFreeze = depositService.getDepositByUserId(userId).availableBalance();

        BigDecimal depositAmount = new BigDecimal("10000");
        BigDecimal feeAmount = new BigDecimal("4000");

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

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, depositAmount, feeAmount);

        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        diagnosticRequestService.approveReport(requestId, 999L);

        // 1) PHASE 2: deposit remains frozen - approveReport no longer unfreezes it.
        assertThat(depositService.getDepositByUserId(userId).availableBalance())
                .as("blockedAmount must remain at its post-freeze value - release is deferred to finalization")
                .isEqualByComparingTo(availableBeforeFreeze.subtract(depositAmount));

        // 2) No UNBLOCK transaction created.
        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(userId);
        boolean hasUnblock = transactions.stream()
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK
                        && t.getAmount().compareTo(depositAmount) == 0);
        assertThat(hasUnblock).as("no UNBLOCK DepositTransaction should exist - approveReport never releases in PHASE 2").isFalse();

        // 3) SellerDiagnosticCommitment persists (never deleted) in AWAITING_FINALIZATION.
        var commitment = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId);
        assertThat(commitment)
                .as("the seller diagnostic commitment must still exist after approval")
                .isPresent();
        assertThat(commitment.orElseThrow().getStatus())
                .as("the commitment must have transitioned to AWAITING_FINALIZATION")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        // 4) Report status actually persisted as APPROVED - reloaded fresh from the repository,
        // proving the earlier plain save() was genuinely flushed and not silently discarded by
        // the subsequent clearAutomatically=true commitment-transition query.
        DiagnosticRequest reloaded = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.APPROVED);
    }
}
