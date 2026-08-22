// diagnostic/application/DiagnosticApproveRejectConcurrencyIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
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
import org.junit.jupiter.api.RepeatedTest;
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

/**
 * Follow-up item 1: DiagnosticRequest has no @Version and no row lock on read
 * (findById is a plain SELECT). This test uses REAL concurrent threads against the
 * real (H2, test-profile) database - not Mockito - to observe what actually happens
 * when approveReport/rejectReport race on the same COMPLETED request.
 *
 * Not Testcontainers/PostgreSQL: this project has no Testcontainers dependency and
 * Docker is not running on this machine (confirmed earlier in this engagement) -
 * adding either would require a new dependency, out of scope for a read/prove-first
 * follow-up. H2's default isolation (READ_COMMITTED) and lack of any table-level lock
 * on this path are the same properties that make the race possible on Postgres too;
 * this is a genuine multi-threaded, multi-connection, multi-transaction test, not a
 * simulation - the important caveat is that it is not proven against Postgres
 * specifically.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Диагностика: конкурентные approve/reject на один и тот же запрос")
class DiagnosticApproveRejectConcurrencyIntegrationTest {

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

    private record Setup(Long userId, Long requestId, BigDecimal depositAmount, BigDecimal availableBeforeFreeze) {}

    private Setup setUpCompletedRequestWithFrozenDeposit() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User applicant = new User(null, "race" + System.nanoTime(), "race" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(applicant).getId();

        depositService.createDeposit(userId, new BigDecimal("50000"));
        BigDecimal availableBeforeFreeze = depositService.getDepositByUserId(userId).availableBalance();
        BigDecimal depositAmount = new BigDecimal("10000");

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("R" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(depositAmount);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        DiagnosticRequest saved = diagnosticRequestRepository.save(request);
        Long requestId = saved.getId();

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, depositAmount, new BigDecimal("4000"));

        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        return new Setup(userId, requestId, depositAmount, availableBeforeFreeze);
    }

    /**
     * Runs opA and opB concurrently (synchronized to start together via a barrier),
     * then asserts the safety invariants that must hold REGARDLESS of which one wins
     * the race or how exactly the two transactions interleaved.
     */
    private void runConcurrentPairAndAssertInvariants(String label, Runnable opA, Runnable opB, Setup setup) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable wrappedA = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                opA.run();
            } catch (Throwable t) {
                errA.set(t);
            }
        };
        Runnable wrappedB = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                opB.run();
            } catch (Throwable t) {
                errB.set(t);
            }
        };

        executor.submit(wrappedA);
        executor.submit(wrappedB);
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        // ---- Safety invariants that must hold no matter how the race resolved ----

        // Exactly one side may have thrown, both may have thrown (double-guard trip),
        // or (if truly serialized by DB timing) neither. What must NEVER happen: both
        // succeed AND the deposit ends up double-released or left still frozen.
        boolean aFailed = errA.get() != null;
        boolean bFailed = errB.get() != null;

        // PHASE 2: approveReport/rejectReport no longer touch the Deposit ledger at all - they
        // only transition the commitment FROZEN -> AWAITING_FINALIZATION. So the meaningful
        // post-race invariants are now: (1) no UNBLOCK transaction is ever created by this race,
        // (2) the commitment persists (never deleted), (3) it ends up in AWAITING_FINALIZATION
        // exactly once (never lost, never duplicated - the atomic UPDATE...WHERE guard is the
        // proof), (4) the deposit remains frozen at its post-freeze balance throughout.
        List<DepositTransaction> transactions = depositRepository.findTransactionsByUserId(setup.userId());
        long unblockCount = transactions.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK)
                .filter(t -> setup.depositAmount().compareTo(t.getAmount()) == 0)
                .count();

        Deposit finalDeposit = depositRepository.findByUserId(setup.userId()).orElseThrow();
        var finalCommitment = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(setup.requestId());

        DiagnosticRequest finalRequest = diagnosticRequestRepository.findById(setup.requestId()).orElseThrow();

        System.out.printf(
                "[%s] aFailed=%s bFailed=%s unblockCount=%d commitmentStatus=%s finalStatus=%s finalAvailable=%s errA=%s errB=%s%n",
                label, aFailed, bFailed, unblockCount,
                finalCommitment.map(c -> c.getStatus().name()).orElse("MISSING"), finalRequest.getStatus(),
                finalDeposit.getAvailableBalance(),
                errA.get() == null ? "-" : errA.get().getClass().getSimpleName(),
                errB.get() == null ? "-" : errB.get().getClass().getSimpleName()
        );

        // Core financial invariants (must hold unconditionally):
        assertThat(unblockCount)
                .as("[%s] PHASE 2: approve/reject must never unfreeze the deposit - that is deferred " +
                        "to a future finalization phase", label)
                .isEqualTo(0L);
        assertThat(finalCommitment)
                .as("[%s] the seller diagnostic commitment must never be deleted by approve/reject", label)
                .isPresent();
        assertThat(finalCommitment.orElseThrow().getStatus())
                .as("[%s] the commitment must end up AWAITING_FINALIZATION exactly once, regardless of race outcome", label)
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(finalDeposit.getAvailableBalance())
                .as("[%s] the deposit must remain frozen at its post-freeze balance - approve/reject never releases it", label)
                .isEqualByComparingTo(setup.availableBeforeFreeze().subtract(setup.depositAmount()));
    }

    @RepeatedTest(5)
    @DisplayName("A. approve + reject конкурентно")
    void approveAndReject_Concurrent() {
        Setup s = setUpCompletedRequestWithFrozenDeposit();
        runConcurrentPairAndAssertInvariants("approve+reject",
                () -> diagnosticRequestService.approveReport(s.requestId(), 1L),
                () -> diagnosticRequestService.rejectReport(s.requestId(), 2L, "конкурентный reject", DiagnosticRejectionCategory.VEHICLE_CONDITION),
                s);
    }

    @RepeatedTest(5)
    @DisplayName("B. reject + approve конкурентно")
    void rejectAndApprove_Concurrent() {
        Setup s = setUpCompletedRequestWithFrozenDeposit();
        runConcurrentPairAndAssertInvariants("reject+approve",
                () -> diagnosticRequestService.rejectReport(s.requestId(), 1L, "конкурентный reject", DiagnosticRejectionCategory.VEHICLE_CONDITION),
                () -> diagnosticRequestService.approveReport(s.requestId(), 2L),
                s);
    }

    @RepeatedTest(5)
    @DisplayName("C. reject + reject конкурентно")
    void rejectAndReject_Concurrent() {
        Setup s = setUpCompletedRequestWithFrozenDeposit();
        runConcurrentPairAndAssertInvariants("reject+reject",
                () -> diagnosticRequestService.rejectReport(s.requestId(), 1L, "reject A", DiagnosticRejectionCategory.VEHICLE_CONDITION),
                () -> diagnosticRequestService.rejectReport(s.requestId(), 2L, "reject B", DiagnosticRejectionCategory.VEHICLE_CONDITION),
                s);
    }

    @RepeatedTest(5)
    @DisplayName("D. approve + approve конкурентно")
    void approveAndApprove_Concurrent() {
        Setup s = setUpCompletedRequestWithFrozenDeposit();
        runConcurrentPairAndAssertInvariants("approve+approve",
                () -> diagnosticRequestService.approveReport(s.requestId(), 1L),
                () -> diagnosticRequestService.approveReport(s.requestId(), 2L),
                s);
    }
}
