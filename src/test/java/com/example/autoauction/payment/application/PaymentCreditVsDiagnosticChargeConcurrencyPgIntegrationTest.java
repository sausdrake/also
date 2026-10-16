// payment/application/PaymentCreditVsDiagnosticChargeConcurrencyPgIntegrationTest.java
package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentFinalizationService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-D: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#creditFromPayment} (D3: pessimistic {@code SELECT ... FOR UPDATE}, held
 * continuously from read to write within ONE physical transaction owned by
 * {@link PaymentConfirmationService#confirmPayment}) and
 * {@link DepositService#chargeForDiagnosticRequest} (unlocked {@code findById} read, plain
 * deferred {@code save()} - confirmed by direct read to be structurally identical in shape to
 * {@code withdraw}, D5-A's already-proven-safe opponent).
 *
 * <p>Recon correction (do not trust the method's own local Javadoc in isolation): the ACTUAL
 * current production caller of {@code chargeForDiagnosticRequest} is
 * {@link SellerDiagnosticCommitmentFinalizationService#finalizeAsOutcomeB}, which - in ONE plain
 * {@code @Transactional} (REQUIRED, deliberately invoked from a non-transactional caller such as
 * {@code AdminDiagnosticController}, so this IS the outermost physical transaction) - first
 * performs an atomic conditional claim on the {@code SellerDiagnosticCommitment} row
 * ({@code AWAITING_FINALIZATION -> FINALIZED}, a plain conditional {@code UPDATE}, no locking of
 * its own) and THEN calls {@code chargeForDiagnosticRequest} in the SAME transaction. A failure in
 * the deposit-side mutation therefore rolls back the commitment's claim too - by design, per that
 * service's own Javadoc.
 *
 * <p>Recon also confirms: the diagnostic-request freeze/charge flow does NOT use
 * {@code FrozenDeposit}/{@code deposit_frozen} at all (Phase 1 seller-commitment redesign) - the
 * "frozen" amount lives only in {@code Deposit.blockedAmount} plus the separate
 * {@code SellerDiagnosticCommitment} table's own {@code committedAmount} snapshot.
 *
 * <p>Because {@code creditFromPayment} never references the diagnostic/commitment module in any
 * way (confirmed: zero {@code diagnostic.*} imports in the payment module), and the commitment's
 * own atomic claim is a single, non-blocking conditional UPDATE on an entirely different table,
 * there is no possible reverse-order lock acquisition between the two operations - the only
 * shared, contended resource is the {@code Deposit} row itself, making this pairing structurally
 * identical to D5-A's already-proven-safe {@code creditFromPayment} vs {@code withdraw} case.
 *
 * <p>Both financial operations are driven through their real production entry points -
 * {@link PaymentConfirmationOrchestrator#confirmPayment} and
 * {@link SellerDiagnosticCommitmentFinalizationService#finalizeAsOutcomeB} - never by calling
 * {@code DepositService} methods directly. All synchronization is via a {@code @SpyBean} around
 * the real, Testcontainers-backed {@link DepositRepository}, gating only TIMING via
 * {@link CountDownLatch} - never faking behaviour or throwing a manufactured exception.
 */
@DisplayName("D5-D: creditFromPayment vs chargeForDiagnosticRequest — real-PostgreSQL concurrency proof")
class PaymentCreditVsDiagnosticChargeConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private SellerDiagnosticCommitmentFinalizationService finalizationService;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal COMMITTED_AMOUNT = new BigDecimal("10000.00");
    private static final BigDecimal FEE_AMOUNT = new BigDecimal("4000.00");
    private static final BigDecimal REFUND_AMOUNT = COMMITTED_AMOUNT.subtract(FEE_AMOUNT);
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7000.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;

    @BeforeEach
    void publishPolicyVersion() {
        // freezeCommitment fails closed without a published PolicyVersion (X3-E).
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
    }

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5d" + System.nanoTime(), "d5d" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    /**
     * Real production path: create (PENDING) -> freeze (real commitment + blockedAmount) ->
     * complete -> rejectReport(VEHICLE_CONDITION) (real path: request -> REJECTED, commitment
     * FROZEN -> AWAITING_FINALIZATION). Mirrors DiagnosticRejectUnfreezeIntegrationTest's own
     * established, already-legitimate convention of setting mid-lifecycle status directly via the
     * repository rather than driving the full assignment/submission workflow.
     */
    private Long newAwaitingFinalizationDiagnosticRequest(Long userId) {
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

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, COMMITTED_AMOUNT, FEE_AMOUNT);

        DiagnosticRequest saved = diagnosticRequestRepository.findById(requestId).orElseThrow();
        saved.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(saved);

        diagnosticRequestService.rejectReport(requestId, 999L, "Недостаточно фотографий",
                DiagnosticRejectionCategory.VEHICLE_CONDITION);

        return requestId;
    }

    private String newSucceedingPayment(Long userId, BigDecimal amount) {
        String ref = "PAY-D5D-" + System.nanoTime();
        String providerRef = "PSP-D5D-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, amount, null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, amount, "KGS", "TXN-D5D-" + System.nanoTime()));
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

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D5-D synchronization latch timed out - forced interleaving failed to occur");
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
    // credit commits first (or while charge is in progress - observationally identical, see
    // class Javadoc / D5-A precedent) -> charge's stale write is rejected, and its WHOLE
    // transaction - including the commitment's FINALIZED claim - rolls back atomically.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("credit holds its pessimistic lock and commits while the diagnostic charge is in progress -> " +
            "credit always succeeds, charge's stale write is rejected by @Version, and the WHOLE finalization " +
            "(including the commitment's FINALIZED claim) rolls back atomically")
    void creditCommitsFirst_ChargeStaleWriteRejected_WholeFinalizationRollsBack() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long requestId = newAwaitingFinalizationDiagnosticRequest(userId);
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch creditLockAcquired = new CountDownLatch(1);
        CountDownLatch creditCommitted = new CountDownLatch(1);
        // TEST-01: the other side must have READ the deposit before credit commits - otherwise a
        // fast credit (a warm JVM) commits first, the read is fresh, no stale write, and the test
        // failed whenever another class had warmed up the same code. Credit's write waits for it;
        // the read is a plain SELECT, never blocked by credit's FOR UPDATE lock, so no deadlock.
        CountDownLatch chargeRead = new CountDownLatch(1);

        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditLockAcquired.countDown();
            return result;
        }).when(depositRepositorySpy).findByUserIdForUpdate(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("charge-thread")) {
                Object read = awaitThenCallReal(inv, creditLockAcquired);
                chargeRead.countDown();
                return read;
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findById(depositId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("charge-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            if (Thread.currentThread().getName().equals("credit-thread")) {
                return awaitThenCallReal(inv, chargeRead);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> chargeErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> {
                    try { orchestrator.confirmPayment(ref); }
                    catch (Throwable t) { creditErr.set(t); }
                    finally { creditCommitted.countDown(); }
                },
                "charge-thread", () -> { try { finalizationService.finalizeAsOutcomeB(requestId); } catch (Throwable t) { chargeErr.set(t); } }
        );

        assertThat(creditErr.get()).as("credit must always succeed - it holds its pessimistic lock continuously").isNull();
        assertThat(chargeErr.get())
                .as("the charge's stale write must be rejected by the real @Version check")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // --- fresh reads only: whole finalization transaction rolled back, including the claim ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("NO LOST UPDATE / NO DOUBLE CREDIT: only credit's delta applied, charge's was rejected")
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("NO BLOCKED-AMOUNT CORRUPTION: charge's mutation rolled back - still fully committed")
                .isEqualByComparingTo(COMMITTED_AMOUNT);
        assertThat(afterRace.getVersion()).as("only credit's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        SellerDiagnosticCommitment commitmentAfter = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(commitmentAfter.getStatus())
                .as("NO PARTIALLY-COMPLETED FINANCIAL LIFECYCLE: the atomic claim rolled back together with " +
                        "the failed deposit mutation - the commitment must NOT be left FINALIZED")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitmentAfter.getFinalizationOutcome()).as("never populated - rolled back").isNull();
        assertThat(commitmentAfter.getFinalizedAt()).as("never populated - rolled back").isNull();

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.CHARGE))
                .as("NO ORPHAN LEDGER ENTRY: the failed charge's CHARGE row rolled back").isEqualTo(0);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK))
                .as("NO ORPHAN LEDGER ENTRY: the failed charge's UNBLOCK row rolled back").isEqualTo(0);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // DiagnosticRequest itself is untouched by finalizeAsOutcomeB - it only ever reads it once
        // for the eligibility check, confirmed by direct source inspection.
        DiagnosticRequest requestAfter = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(requestAfter.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
    }

    // ======================================================================
    // Diagnostic charge fully commits before credit even attempts its pessimistic lock -> both
    // survive, applied sequentially, no lost update either way.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("diagnostic charge commits before credit acquires its lock -> both succeed, correct combined final state")
    void chargeCommitsFirst_BothSucceed() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long requestId = newAwaitingFinalizationDiagnosticRequest(userId);
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch chargeCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, chargeCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(userId);

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> chargeErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "charge-thread", () -> {
                    try { finalizationService.finalizeAsOutcomeB(requestId); }
                    catch (Throwable t) { chargeErr.set(t); }
                    finally { chargeCommitted.countDown(); }
                }
        );

        assertThat(chargeErr.get()).as("charge must succeed - nothing contends with it yet").isNull();
        assertThat(creditErr.get()).as("credit always re-locks fresh state - never conflicts").isNull();

        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("both mutations survive: initial - fee + credit")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(FEE_AMOUNT).add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("charge released the full committed amount")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getVersion()).as("two successful mutations").isEqualTo(versionBeforeRace + 2);

        SellerDiagnosticCommitment commitmentAfter = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(commitmentAfter.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitmentAfter.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.B);
        assertThat(commitmentAfter.getRefundAmount()).isEqualByComparingTo(REFUND_AMOUNT);
        assertThat(commitmentAfter.getPenaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.CHARGE)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.CHARGE)).isEqualByComparingTo(FEE_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualByComparingTo(REFUND_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
