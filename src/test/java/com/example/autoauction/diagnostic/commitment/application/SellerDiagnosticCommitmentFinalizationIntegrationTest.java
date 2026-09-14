// diagnostic/commitment/application/SellerDiagnosticCommitmentFinalizationIntegrationTest.java
package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 3B: real-database proof of Outcome B finalization end-to-end. Deliberately NOT
 * @Transactional at the class level - each call to a @Transactional service method must get
 * its own genuine, independently-committing transaction (matching the architecture-lock
 * decision that rejectReport and finalizeAsOutcomeB run in SEPARATE transactions), mirroring
 * DiagnosticApproveRejectConcurrencyIntegrationTest's own non-transactional setup for the
 * same reason.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PHASE 3B: Outcome B finalization - интеграционные тесты")
class SellerDiagnosticCommitmentFinalizationIntegrationTest {

    @Autowired
    private DiagnosticRequestService diagnosticRequestService;

    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;

    @Autowired
    private SellerDiagnosticCommitmentFinalizationService finalizationService;

    @Autowired
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;

    @Autowired
    private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PolicyVersionService policyVersionService;

    // X3-E: freezeCommitment() now snapshots the currently published PolicyVersion's id and
    // fails closed if none exists. No @Transactional on this class - each test publishes
    // another version, harmless.
    @BeforeEach
    void publishPolicyVersion() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
    }

    private record Setup(Long userId, Long requestId, Long depositId,
                          BigDecimal committedAmount, BigDecimal feeAmount, BigDecimal availableBeforeFreeze) {}

    /**
     * create -> freeze -> COMPLETED -> reject(VEHICLE_CONDITION), mirroring
     * DiagnosticRejectUnfreezeIntegrationTest's own setup shape exactly, up to (and
     * including) the point where the commitment durably reaches AWAITING_FINALIZATION.
     * feeAmount is configured explicitly per test (not relying on any global default) -
     * these are test fixture values representing "what an administrator configured", not
     * production defaults.
     */
    private Setup setUpAwaitingFinalizationViaVehicleConditionReject(BigDecimal feeAmount) {
        return setUpAwaitingFinalizationViaReject(feeAmount, DiagnosticRejectionCategory.VEHICLE_CONDITION);
    }

    private Setup setUpAwaitingFinalizationViaReject(BigDecimal feeAmount, DiagnosticRejectionCategory category) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User applicant = new User(null, "fin" + System.nanoTime(), "fin" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(applicant).getId();

        depositService.createDeposit(userId, new BigDecimal("50000"));
        BigDecimal availableBeforeFreeze = depositService.getDepositByUserId(userId).availableBalance();
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();

        BigDecimal committedAmount = new BigDecimal("10000");

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("FIN" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(committedAmount);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = diagnosticRequestRepository.save(request);
        Long requestId = saved.getId();

        // freezeCommitment takes committedAmount/feeAmount as direct parameters - it never
        // reads SystemSettingsService itself (that read already happened, once, in
        // DiagnosticRequestService.createRequest, which is Phase 2 behavior this test does
        // not need to re-drive) - so no system_settings interaction is needed here at all,
        // exactly mirroring the existing DiagnosticRejectUnfreezeIntegrationTest precedent.
        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, committedAmount, feeAmount);

        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        diagnosticRequestService.rejectReport(requestId, 999L, "Причина отклонения (fixture)", category);

        SellerDiagnosticCommitment afterReject = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(afterReject.getStatus())
                .as("setup precondition: reject must durably reach AWAITING_FINALIZATION before any finalize test runs")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        return new Setup(userId, requestId, depositId, committedAmount, feeAmount, availableBeforeFreeze);
    }

    // PHASE 3B corrective slice: real-DB proof that the eligibility guard added to support
    // the manual retry endpoint actually protects SELLER_VIOLATION requests, both via the
    // automatic rejectReport-triggered path (never even attempted for SELLER_VIOLATION - see
    // AdminDiagnosticController) AND via a direct call to finalizeAsOutcomeB (simulating what
    // the retry endpoint would do if an admin called it for the wrong request).

    @Test
    @DisplayName("SELLER_VIOLATION: commitment остаётся AWAITING_FINALIZATION навсегда (no auto-finalize), " +
            "и прямой вызов finalizeAsOutcomeB (как retry endpoint) отклоняется IllegalStateException без мутаций")
    void sellerViolation_NeverAutoFinalized_AndRetryRejected() {
        Setup s = setUpAwaitingFinalizationViaReject(new BigDecimal("4000"), DiagnosticRejectionCategory.SELLER_VIOLATION);

        // rejectReport's own controller wiring only calls finalize for VEHICLE_CONDITION, so
        // by construction nothing has auto-finalized this commitment - the setup's own
        // precondition assertion (AWAITING_FINALIZATION) already proves this once; re-asserted
        // here for clarity of intent.
        SellerDiagnosticCommitment beforeRetry = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(beforeRetry.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        Deposit depositBefore = depositRepository.findByUserId(s.userId()).orElseThrow();

        assertThatThrownBy(() -> finalizationService.finalizeAsOutcomeB(s.requestId()))
                .isInstanceOf(IllegalStateException.class);

        SellerDiagnosticCommitment afterRetry = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(afterRetry.getStatus())
                .as("must remain AWAITING_FINALIZATION - Outcome C is not implemented, must never be silently treated as B")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        Deposit depositAfter = depositRepository.findByUserId(s.userId()).orElseThrow();
        assertThat(depositAfter.getBalance()).isEqualByComparingTo(depositBefore.getBalance());
        assertThat(depositAfter.getBlockedAmount()).isEqualByComparingTo(depositBefore.getBlockedAmount());

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(s.userId());
        assertThat(transactions.stream()
                .noneMatch(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference())
                        && (t.getType() == DepositTransaction.TransactionType.CHARGE
                        || t.getType() == DepositTransaction.TransactionType.UNBLOCK)))
                .as("no CHARGE/UNBLOCK must ever be created for a SELLER_VIOLATION request")
                .isTrue();
    }

    @Test
    @DisplayName("Happy path: reject(VEHICLE_CONDITION) durably commits, ОТДЕЛЬНЫЙ finalize -> FINALIZED/B, " +
            "Deposit и DepositTransaction корректны")
    void finalizeAsOutcomeB_HappyPath_FullDbState() {
        Setup s = setUpAwaitingFinalizationViaVehicleConditionReject(new BigDecimal("4000"));

        finalizationService.finalizeAsOutcomeB(s.requestId());

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.B);
        assertThat(commitment.getPenaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("6000");
        assertThat(commitment.getFinalizedAt()).isNotNull();

        Deposit deposit = depositRepository.findByUserId(s.userId()).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(deposit.getBalance()).isEqualByComparingTo(new BigDecimal("50000").subtract(new BigDecimal("4000")));
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo(s.availableBeforeFreeze().subtract(new BigDecimal("4000")));

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(s.userId());
        long chargeCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE)
                .filter(t -> t.getAmount().compareTo(new BigDecimal("4000")) == 0)
                .count();
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK)
                .filter(t -> t.getAmount().compareTo(new BigDecimal("6000")) == 0)
                .count();
        assertThat(chargeCount).isEqualTo(1L);
        assertThat(unblockCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("fee == committed: refund=0, ровно один CHARGE, ни одного UNBLOCK")
    void finalizeAsOutcomeB_FeeEqualsCommitted_NoUnblockTransaction() {
        Setup s = setUpAwaitingFinalizationViaVehicleConditionReject(new BigDecimal("10000"));

        finalizationService.finalizeAsOutcomeB(s.requestId());

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(s.userId());
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK)
                .filter(t -> t.getReference() != null && t.getReference().equals("DIAGNOSTIC_" + s.requestId()))
                .count();
        assertThat(unblockCount).isZero();
    }

    @Test
    @DisplayName("Снапшот неизменяем: finalize использует ЗНАЧЕНИЯ, персистированные на commitment " +
            "(перечитанные заново из БД), а не какой-либо иной источник - SellerDiagnosticCommitmentFinalizationService " +
            "структурно не имеет зависимости на SystemSettingsService (см. конструктор), поэтому единственный " +
            "возможный источник fee/committed - сама запись commitment")
    void finalizeAsOutcomeB_UsesPersistedCommitmentSnapshot() {
        Setup s = setUpAwaitingFinalizationViaVehicleConditionReject(new BigDecimal("4000"));

        // Fresh reload from DB, proving the values finalize will use are exactly what was
        // durably persisted at freeze time, not merely an in-memory Java reference carried
        // over from setup.
        SellerDiagnosticCommitment reloadedBeforeFinalize = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(reloadedBeforeFinalize.getCommittedAmount()).isEqualByComparingTo("10000");
        assertThat(reloadedBeforeFinalize.getDiagnosticFeeAmount()).isEqualByComparingTo("4000");

        finalizationService.finalizeAsOutcomeB(s.requestId());

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("6000");

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(s.userId());
        assertThat(transactions.stream()
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.CHARGE
                        && t.getAmount().compareTo(new BigDecimal("4000")) == 0))
                .as("CHARGE must use exactly the persisted snapshot (4000)")
                .isTrue();
    }

    @Test
    @DisplayName("Повторная финализация: exactly one CHARGE, exactly one UNBLOCK, второй вызов - идемпотентный no-op")
    void finalizeAsOutcomeB_RepeatedFinalization_Idempotent() {
        Setup s = setUpAwaitingFinalizationViaVehicleConditionReject(new BigDecimal("4000"));

        finalizationService.finalizeAsOutcomeB(s.requestId());
        finalizationService.finalizeAsOutcomeB(s.requestId());

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(s.userId());
        long chargeCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE)
                .filter(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference()))
                .count();
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK)
                .filter(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference()))
                .count();
        assertThat(chargeCount).isEqualTo(1L);
        assertThat(unblockCount).isEqualTo(1L);

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
    }

    @Test
    @DisplayName("Ledger failure -> полный rollback (commitment остаётся AWAITING_FINALIZATION, Deposit не тронут, " +
            "ни CHARGE ни UNBLOCK не персистятся), затем retry успешен")
    void finalizeAsOutcomeB_LedgerFailure_FullRollback_ThenRetrySucceeds() {
        Setup s = setUpAwaitingFinalizationViaVehicleConditionReject(new BigDecimal("4000"));

        // Force a real, reachable failure inside Deposit.chargeFromFrozen: artificially
        // reduce blockedAmount below committedAmount, simulating some other process having
        // already released part of the block - chargeFromFrozen's own precondition
        // (blockedAmount >= totalFrozen) will then throw IllegalStateException for real,
        // inside the SAME transaction as the atomic claim.
        Deposit deposit = depositRepository.findByUserId(s.userId()).orElseThrow();
        BigDecimal blockedBeforeSabotage = deposit.getBlockedAmount();
        deposit.setBlockedAmount(new BigDecimal("1"));
        depositRepository.save(deposit);

        assertThatThrownBy(() -> finalizationService.finalizeAsOutcomeB(s.requestId()))
                .isInstanceOf(IllegalStateException.class);

        // Verify full rollback: commitment reverted to AWAITING_FINALIZATION, no finalization
        // fields set, no CHARGE/UNBLOCK persisted for this request.
        SellerDiagnosticCommitment afterFailure = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(afterFailure.getFinalizationOutcome()).isNull();
        assertThat(afterFailure.getFinalizedAt()).isNull();
        assertThat(afterFailure.getPenaltyAmount()).isNull();
        assertThat(afterFailure.getRefundAmount()).isNull();

        List<DepositTransaction> afterFailureTx = depositRepository.findTransactionsByUserId(s.userId());
        assertThat(afterFailureTx.stream()
                .noneMatch(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference())
                        && (t.getType() == DepositTransaction.TransactionType.CHARGE
                        || t.getType() == DepositTransaction.TransactionType.UNBLOCK)))
                .as("no CHARGE/UNBLOCK must exist for this request after a rolled-back finalization")
                .isTrue();

        // Repair the sabotaged state and retry - must succeed cleanly, exactly once.
        Deposit toRepair = depositRepository.findByUserId(s.userId()).orElseThrow();
        toRepair.setBlockedAmount(blockedBeforeSabotage);
        depositRepository.save(toRepair);

        finalizationService.finalizeAsOutcomeB(s.requestId());

        SellerDiagnosticCommitment afterRetry = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);

        List<DepositTransaction> afterRetryTx = depositRepository.findTransactionsByUserId(s.userId());
        long chargeCount = afterRetryTx.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE)
                .filter(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference()))
                .count();
        assertThat(chargeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Конкурентная финализация: два потока finalize одного commitment -> ровно один CHARGE, " +
            "ровно один UNBLOCK, единственный FINALIZED результат")
    void finalizeAsOutcomeB_ConcurrentFinalization_ExactlyOnce() throws Exception {
        Setup s = setUpAwaitingFinalizationViaVehicleConditionReject(new BigDecimal("4000"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable attempt = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                finalizationService.finalizeAsOutcomeB(s.requestId());
            } catch (Throwable t) {
                // captured below per-thread
                throw new RuntimeException(t);
            }
        };

        var futureA = executor.submit(() -> {
            try {
                attempt.run();
            } catch (Throwable t) {
                errA.set(t);
            }
        });
        var futureB = executor.submit(() -> {
            try {
                attempt.run();
            } catch (Throwable t) {
                errB.set(t);
            }
        });

        futureA.get(15, TimeUnit.SECONDS);
        futureB.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Both may succeed (one truly finalizes, the other observes idempotent no-op) - what
        // must never happen is either throwing an unexpected error, or two financial mutations.
        System.out.printf("[concurrent finalize] errA=%s errB=%s%n",
                errA.get() == null ? "-" : errA.get().getClass().getSimpleName(),
                errB.get() == null ? "-" : errB.get().getClass().getSimpleName());

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(s.requestId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);

        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(s.userId());
        long chargeCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.CHARGE)
                .filter(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference()))
                .count();
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK)
                .filter(t -> "DIAGNOSTIC_".concat(s.requestId().toString()).equals(t.getReference()))
                .count();

        assertThat(chargeCount)
                .as("exactly one CHARGE must persist regardless of which thread won the race")
                .isEqualTo(1L);
        assertThat(unblockCount)
                .as("exactly one UNBLOCK must persist regardless of which thread won the race")
                .isEqualTo(1L);

        Deposit deposit = depositRepository.findByUserId(s.userId()).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(deposit.getBalance()).isEqualByComparingTo(new BigDecimal("50000").subtract(new BigDecimal("4000")));
    }
}
