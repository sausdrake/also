// payment/application/PaymentCreditVsSellerViolationPenaltyConcurrencyPgIntegrationTest.java
package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.application.SellerViolationResolutionEngine;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolutionItem;
import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity;
import com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-E: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#creditFromPayment} (D3: pessimistic {@code SELECT ... FOR UPDATE} via
 * {@code findByUserIdForUpdate}, held continuously from read to write within ONE physical
 * transaction owned by {@link PaymentConfirmationService#confirmPayment}) and
 * {@link DepositService#chargeSellerViolationPenalty} (unlocked {@code findById} read, plain
 * deferred {@code save()} - confirmed by direct source read to be structurally identical in shape
 * to {@code chargeForDiagnosticRequest}, D5-D's already-proven-safe opponent, sharing the same
 * {@link com.example.autoauction.deposit.domain.Deposit#chargeFromFrozen} primitive).
 *
 * <p>Recon-confirmed actual production caller: {@link SellerViolationResolutionEngine#resolve},
 * which - in ONE plain {@code @Transactional} (REQUIRED, invoked from a non-transactional caller
 * such as {@code AdminDiagnosticController}, so this IS the outermost physical transaction) -
 * first performs an atomic conditional claim on the {@code SellerDiagnosticCommitment} row
 * ({@code AWAITING_FINALIZATION -> FINALIZED/C}, a plain conditional {@code UPDATE}, no locking of
 * its own), persists the {@code SellerViolationResolution}/{@code Item} audit rows, and THEN calls
 * {@code chargeSellerViolationPenalty} in the SAME transaction. A failure in the deposit-side
 * mutation therefore rolls back the claim and the audit rows too - by design, per that engine's
 * own claim-first discipline (identical to {@code SellerDiagnosticCommitmentFinalizationService}).
 *
 * <p>Because {@code creditFromPayment} never references the diagnostic/commitment module in any
 * way, and the commitment's own atomic claim is a single, non-blocking conditional UPDATE on an
 * entirely different table, there is no possible reverse-order lock acquisition between the two
 * operations - the only shared, contended resource is the {@code Deposit} row itself, making this
 * pairing structurally identical to D5-D's already-proven-safe
 * {@code creditFromPayment} vs {@code chargeForDiagnosticRequest} case.
 *
 * <p>{@code PenaltyType.FIXED_AMOUNT} is used (rather than {@code PERCENTAGE}) so the final
 * financial formula is a plain, transparent subtraction, per the D5-E PLAN/VERIFY fixture design.
 *
 * <p>Both financial operations are driven through their real production entry points -
 * {@link PaymentConfirmationOrchestrator#confirmPayment} and
 * {@link SellerViolationResolutionEngine#resolve} - never by calling {@code DepositService}
 * methods directly. All synchronization is via a {@code @SpyBean} around the real,
 * Testcontainers-backed {@link DepositRepository}, gating only TIMING via {@link CountDownLatch} -
 * never faking behaviour or throwing a manufactured exception.
 */
@DisplayName("D5-E: creditFromPayment vs chargeSellerViolationPenalty — real-PostgreSQL concurrency proof")
class PaymentCreditVsSellerViolationPenaltyConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private SellerViolationResolutionEngine sellerViolationResolutionEngine;
    @Autowired private SellerViolationResolutionRepository sellerViolationResolutionRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal COMMITTED_AMOUNT = new BigDecimal("10000.00");
    private static final BigDecimal PENALTY_AMOUNT = new BigDecimal("3500.00");
    private static final BigDecimal REFUND_AMOUNT = COMMITTED_AMOUNT.subtract(PENALTY_AMOUNT);
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7000.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;
    private static final Long RESOLVED_BY_ADMIN_ID = 999L;

    private record PolicyFixture(Long policyVersionId, Long violationDefinitionId) {}

    /**
     * Publishes a NEW PolicyVersion with one FIXED_AMOUNT violation configured - MUST run before
     * {@code freezeCommitment} so the commitment snapshots this exact policyVersionId (X3-E:
     * freezeCommitment snapshots whichever PolicyVersion is currently published). Mirrors
     * SaleCommitmentResolutionIntegrationTest.publishPolicyWithOneViolation's own established
     * fixture shape, adapted to FIXED_AMOUNT for a transparent penalty formula.
     */
    private PolicyFixture publishPolicyWithFixedViolation() {
        Long violationDefinitionId = violationDefinitionJpaRepository.save(
                new JpaViolationDefinitionEntity("D5E_TEST_VIOLATION_" + System.nanoTime(),
                        "test fixture", OffsetDateTime.now())).getId();
        Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
        policyVersionService.updateDraftViolation(policyVersionId, violationDefinitionId,
                PenaltyType.FIXED_AMOUNT, PENALTY_AMOUNT, 0L);
        Long revision = policyVersionService.getById(policyVersionId).getConfigurationRevision();
        policyVersionService.publish(policyVersionId, 2L, revision);
        return new PolicyFixture(policyVersionId, violationDefinitionId);
    }

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5e" + System.nanoTime(), "d5e" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    /**
     * Real production path: create (PENDING) -> freeze (real commitment + blockedAmount) ->
     * complete -> rejectReport(SELLER_VIOLATION) (real path: request -> REJECTED, commitment
     * FROZEN -> AWAITING_FINALIZATION; confirmed by source that, unlike VEHICLE_CONDITION,
     * SELLER_VIOLATION does NOT auto-invoke any finalization - the commitment is left durably
     * AWAITING_FINALIZATION for a SEPARATE resolve() call). Mirrors
     * DiagnosticRejectUnfreezeIntegrationTest / D5-D's own established, already-legitimate
     * convention of setting the mid-lifecycle DiagnosticRequest status directly via the
     * repository rather than driving the full assignment/submission workflow - orthogonal to
     * this financial concurrency test.
     */
    private Long newAwaitingSellerViolationDiagnosticRequest(Long userId) {
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("WBSDE91070CZ" + System.nanoTime() % 100000);
        request.setDepositFrozen(COMMITTED_AMOUNT);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, COMMITTED_AMOUNT, BigDecimal.ZERO);

        DiagnosticRequest saved = diagnosticRequestRepository.findById(requestId).orElseThrow();
        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        diagnosticRequestService.rejectReport(requestId, RESOLVED_BY_ADMIN_ID, "Продавец нарушил условия сделки",
                DiagnosticRejectionCategory.SELLER_VIOLATION);

        return requestId;
    }

    private String newSucceedingPayment(Long userId, BigDecimal amount) {
        String ref = "PAY-D5E-" + System.nanoTime();
        String providerRef = "PSP-D5E-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, amount, null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, amount, "KGS", "TXN-D5E-" + System.nanoTime()));
        return ref;
    }

    private long ledgerCount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type).count();
    }

    private BigDecimal ledgerAmount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type).findFirst().orElseThrow().getAmount();
    }

    private String ledgerReference(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type).findFirst().orElseThrow().getReference();
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D5-E synchronization latch timed out - forced interleaving failed to occur");
        }
        return inv.callRealMethod();
    }

    private void runTwoThreads(String nameA, Runnable a, String nameB, Runnable b) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread threadA = new Thread(() -> {
            try { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); a.run(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, nameA);
        Thread threadB = new Thread(() -> {
            try { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); b.run(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, nameB);
        threadA.start();
        threadB.start();
        threadA.join(JOIN_TIMEOUT_MS);
        threadB.join(JOIN_TIMEOUT_MS);
        assertThat(threadA.isAlive()).as("thread %s must have finished - no deadlock", nameA).isFalse();
        assertThat(threadB.isAlive()).as("thread %s must have finished - no deadlock", nameB).isFalse();
    }

    // ======================================================================
    // credit commits first (or while the penalty resolution is in progress - observationally
    // identical, see class Javadoc / D5-D precedent) -> penalty's stale write is rejected, and its
    // WHOLE transaction - including the commitment's FINALIZED claim and audit rows - rolls back
    // atomically.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("credit holds its pessimistic lock and commits while the seller-violation penalty is in progress -> " +
            "credit always succeeds, penalty's stale write is rejected by @Version, and the WHOLE resolution " +
            "(including the commitment's FINALIZED claim and audit rows) rolls back atomically")
    void creditCommitsFirst_PenaltyStaleWriteRejected_WholeResolutionRollsBack() throws InterruptedException {
        PolicyFixture policy = publishPolicyWithFixedViolation();
        Long userId = newUserWithDeposit();
        Long requestId = newAwaitingSellerViolationDiagnosticRequest(userId);
        Long commitmentId = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow().getId();
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch creditLockAcquired = new CountDownLatch(1);
        CountDownLatch creditCommitted = new CountDownLatch(1);
        // TEST-01: the other side must have READ the deposit before credit commits - otherwise a
        // fast credit (a warm JVM) commits first, the read is fresh, no stale write, and the test
        // failed whenever another class had warmed up the same code. Credit's write waits for it;
        // the read is a plain SELECT, never blocked by credit's FOR UPDATE lock, so no deadlock.
        CountDownLatch penaltyRead = new CountDownLatch(1);

        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditLockAcquired.countDown();
            return result;
        }).when(depositRepositorySpy).findByUserIdForUpdate(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("penalty-thread")) {
                Object read = awaitThenCallReal(inv, creditLockAcquired);
                penaltyRead.countDown();
                return read;
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findById(depositId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("penalty-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            if (Thread.currentThread().getName().equals("credit-thread")) {
                return awaitThenCallReal(inv, penaltyRead);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> penaltyErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> {
                    try { orchestrator.confirmPayment(ref); }
                    catch (Throwable t) { creditErr.set(t); }
                    finally { creditCommitted.countDown(); }
                },
                "penalty-thread", () -> {
                    try {
                        sellerViolationResolutionEngine.resolve(commitmentId, policy.policyVersionId(),
                                List.of(policy.violationDefinitionId()), RESOLVED_BY_ADMIN_ID,
                                TriggerSource.DIAGNOSTIC_REJECTION, null);
                    } catch (Throwable t) { penaltyErr.set(t); }
                }
        );

        assertThat(creditErr.get()).as("credit must always succeed - it holds its pessimistic lock continuously").isNull();
        assertThat(penaltyErr.get())
                .as("the penalty's stale write must be rejected by the real @Version check")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // --- fresh reads only: whole resolution transaction rolled back, including the claim ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("NO LOST UPDATE / NO DOUBLE CREDIT: only credit's delta applied, penalty's was rejected")
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("NO BLOCKED-AMOUNT CORRUPTION: penalty's mutation rolled back - still fully committed")
                .isEqualByComparingTo(COMMITTED_AMOUNT);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT).subtract(COMMITTED_AMOUNT));
        assertThat(afterRace.getVersion()).as("only credit's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        SellerDiagnosticCommitment commitmentAfter = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(commitmentAfter.getStatus())
                .as("NO PARTIALLY-COMPLETED FINANCIAL LIFECYCLE: the atomic claim rolled back together with " +
                        "the failed deposit mutation - the commitment must NOT be left FINALIZED")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitmentAfter.getFinalizationOutcome()).as("never populated - rolled back").isNull();
        assertThat(commitmentAfter.getPenaltyAmount()).as("never populated - rolled back").isNull();
        assertThat(commitmentAfter.getRefundAmount()).as("never populated - rolled back").isNull();
        assertThat(commitmentAfter.getFinalizedAt()).as("never populated - rolled back").isNull();

        // NO PARTIAL AUDIT TRAIL: the resolution header is only ever persisted in the SAME
        // transaction as the claim - if the claim rolled back, no header (and therefore no items,
        // which cannot exist without a header id) can exist either.
        assertThat(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(commitmentId))
                .as("resolution audit header must be absent - rolled back together with the claim")
                .isEmpty();

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.CHARGE))
                .as("NO ORPHAN LEDGER ENTRY: the failed penalty's CHARGE row rolled back").isEqualTo(0);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK))
                .as("NO ORPHAN LEDGER ENTRY: the failed penalty's UNBLOCK row rolled back").isEqualTo(0);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // DiagnosticRequest itself is untouched by resolve() - it only ever reads the commitment,
        // never the DiagnosticRequest, confirmed by direct source inspection.
        DiagnosticRequest requestAfter = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(requestAfter.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
    }

    // ======================================================================
    // Seller-violation penalty fully commits before credit even attempts its pessimistic lock ->
    // both survive, applied sequentially, no lost update either way.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("seller-violation penalty commits before credit acquires its lock -> both succeed, correct combined final state")
    void penaltyCommitsFirst_BothSucceed() throws InterruptedException {
        PolicyFixture policy = publishPolicyWithFixedViolation();
        Long userId = newUserWithDeposit();
        Long requestId = newAwaitingSellerViolationDiagnosticRequest(userId);
        Long commitmentId = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch penaltyCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, penaltyCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(userId);

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> penaltyErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "penalty-thread", () -> {
                    try {
                        sellerViolationResolutionEngine.resolve(commitmentId, policy.policyVersionId(),
                                List.of(policy.violationDefinitionId()), RESOLVED_BY_ADMIN_ID,
                                TriggerSource.DIAGNOSTIC_REJECTION, null);
                    } catch (Throwable t) { penaltyErr.set(t); }
                    finally { penaltyCommitted.countDown(); }
                }
        );

        assertThat(penaltyErr.get()).as("penalty must succeed - nothing contends with it yet").isNull();
        assertThat(creditErr.get()).as("credit always re-locks fresh state - never conflicts").isNull();

        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("both mutations survive: initial - penalty + credit")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(PENALTY_AMOUNT).add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("penalty released the full committed amount")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(PENALTY_AMOUNT).add(CREDIT_AMOUNT));
        assertThat(afterRace.getVersion()).as("two successful mutations").isEqualTo(versionBeforeRace + 2);

        SellerDiagnosticCommitment commitmentAfter = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(commitmentAfter.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitmentAfter.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.C);
        assertThat(commitmentAfter.getPenaltyAmount()).isEqualByComparingTo(PENALTY_AMOUNT);
        assertThat(commitmentAfter.getRefundAmount()).isEqualByComparingTo(REFUND_AMOUNT);
        assertThat(commitmentAfter.getFinalizedAt()).isNotNull();

        SellerViolationResolution resolution = sellerViolationResolutionRepository
                .findBySellerDiagnosticCommitmentId(commitmentId)
                .orElseThrow(() -> new AssertionError("SellerViolationResolution must exist after a successful resolve()"));
        assertThat(resolution.getAppliedPenaltyAmount()).isEqualByComparingTo(PENALTY_AMOUNT);
        assertThat(resolution.getRefundAmount()).isEqualByComparingTo(REFUND_AMOUNT);

        List<SellerViolationResolutionItem> items =
                sellerViolationResolutionRepository.findItemsByResolutionId(resolution.getId());
        assertThat(items).as("exactly one violation item, no duplicates").hasSize(1);
        assertThat(items.get(0).getViolationDefinitionId()).isEqualTo(policy.violationDefinitionId());
        assertThat(items.get(0).getCalculatedPenaltyAmount()).isEqualByComparingTo(PENALTY_AMOUNT);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.CHARGE)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.CHARGE)).isEqualByComparingTo(PENALTY_AMOUNT);
        assertThat(ledgerReference(userId, DepositTransaction.TransactionType.CHARGE)).isEqualTo("DIAGNOSTIC_" + requestId);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualByComparingTo(REFUND_AMOUNT);
        assertThat(ledgerReference(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualTo("DIAGNOSTIC_" + requestId);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        DiagnosticRequest requestAfter = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(requestAfter.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
    }
}
