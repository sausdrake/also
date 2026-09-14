// diagnostic/application/UnfreezeDiagnosticRequestCommitTimingPgIntegrationTest.java
package com.example.autoauction.diagnostic.application;

import com.example.autoauction.common.application.FinancialRecoveryService;
import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
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
import org.springframework.transaction.UnexpectedRollbackException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-TEST-M (FIXED): proves the D4-class commit-timing defect in
 * {@link DepositService#unfreezeForDiagnosticRequest} is closed, and proves the full
 * failure/recovery/idempotency lifecycle end to end against real PostgreSQL.
 *
 * <p>D5 recon corrected a stale assumption in that method's own Javadoc: it is NOT called from
 * {@code approveReport}/{@code rejectReport} any more (PHASE 2 moved report approval/rejection to
 * {@code markAwaitingFinalization}, which never touches the deposit at all - see
 * {@code DiagnosticRejectUnfreezeIntegrationTest}). The one remaining real production caller,
 * confirmed by direct trace, is:
 * <pre>
 *   DiagnosticRequestService.cancelRequest(requestId, userId)   [@Transactional - OUTERMOST]
 *     -> SellerDiagnosticCommitmentService.releaseCommitment(sellerId, requestId)   [@Transactional, joins]
 *          -> DepositService.unfreezeForDiagnosticRequest(sellerId, requestId, amount)   [@Transactional, joins]
 * </pre>
 * All three are default (REQUIRED) propagation - deliberately UNCHANGED by the D5-IMPLEMENT-M fix,
 * per the PLAN/VERIFY-M finding that this single-caller, single-entity operation (unlike the
 * fan-out {@code unfreezeForAuction}) genuinely needs its current all-or-nothing atomicity: a
 * losing race must roll back the WHOLE {@code cancelRequest} attempt (request stays PENDING,
 * commitment stays FROZEN), never leave a REJECTED request with money still silently frozen.
 *
 * <p>The fix (D5-IMPLEMENT-M) is the single line {@code unfreezeForDiagnosticRequest} now uses:
 * {@code depositRepository.saveAndFlush(deposit)} instead of {@code save(deposit)}, reusing the
 * port/adapter method D4 already introduced. This forces a genuine {@code @Version} conflict to
 * throw synchronously, inside this method's own existing try block, where its existing
 * {@code catch (Exception e) { ...saveFailedOperation...; throw e; }} already does exactly the
 * right thing - no transaction-boundary change was needed, because (unlike D4's
 * {@code unfreezeForAuction}) there is no {@code REQUIRES_NEW} boundary here to create an
 * {@code UnexpectedRollbackException} hazard: the rethrow simply propagates through the one single
 * physical transaction and Spring rolls it back normally.
 *
 * <p>T2 (the racing transaction) is {@link DepositService#withdraw} - a real, independent,
 * production Deposit mutation for the same user, chosen as a plausible real-world concurrent
 * scenario (a seller withdraws available funds while cancelling an unrelated diagnostic request).
 *
 * <p>No production code beyond the one approved line is modified by this test. The
 * {@code @SpyBean} only gates TIMING via {@link CountDownLatch} around real repository calls -
 * every SQL statement still executes for real against a real Testcontainers PostgreSQL instance;
 * no exception is manually thrown.
 */
@DisplayName("D5-TEST-M (FIXED): unfreezeForDiagnosticRequest commit-timing - real PostgreSQL regression proof")
class UnfreezeDiagnosticRequestCommitTimingPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private FailedOperationRepository failedOperationRepository;
    @Autowired private FinancialRecoveryService recoveryService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PolicyVersionService policyVersionService;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal FREEZE_AMOUNT = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("5000.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;

    @BeforeEach
    void publishPolicyVersion() {
        // freezeCommitment fails closed without a published PolicyVersion (X3-E).
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
    }

    private Long newUserWithDeposit() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5m" + System.nanoTime(), "d5m" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    /** Mirrors DiagnosticRejectUnfreezeIntegrationTest's own established fixture shape. */
    private Long newPendingDiagnosticRequestWithFrozenCommitment(Long userId) {
        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("WBSDE91070CZ" + System.nanoTime() % 100000);
        request.setDepositFrozen(FREEZE_AMOUNT);
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, FREEZE_AMOUNT, new BigDecimal("4000"));
        return requestId;
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D5-TEST-M synchronization latch timed out - forced interleaving failed to occur");
        }
        return inv.callRealMethod();
    }

    @RepeatedTest(5)
    @DisplayName("FIXED: cancelRequest's unfreeze loses a real @Version race against a concurrent withdraw -> " +
            "caught inside unfreezeForDiagnosticRequest, FailedOperation recorded, recovery + idempotent retry both verified")
    void cancelRequestUnfreeze_VersionConflictAgainstConcurrentWithdraw_CaughtRecordedAndRecovered() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long requestId = newPendingDiagnosticRequestWithFrozenCommitment(userId);
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();

        CountDownLatch t1ReadDone = new CountDownLatch(1);
        CountDownLatch t2Committed = new CountDownLatch(1);

        // T1 (unfreeze-thread) signals as soon as it has read the (soon-to-be-stale) Deposit.
        // T2 (withdraw-thread) waits for that read before doing its own read, guaranteeing T1's
        // in-memory copy predates T2's mutation.
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("unfreeze-thread")) {
                Object result = inv.callRealMethod();
                t1ReadDone.countDown();
                return result;
            }
            return awaitThenCallReal(inv, t1ReadDone);
        }).when(depositRepositorySpy).findByUserId(userId);

        // T1's own saveAndFlush(deposit) call (inside unfreezeForDiagnosticRequest, post-fix) is
        // where the real @Version check now executes SYNCHRONOUSLY. We delay T1's arrival at this
        // call until T2 has fully committed, guaranteeing T1's flush attempt is the one that loses
        // the race (deterministic interleaving, not a timing gamble).
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("unfreeze-thread")) {
                return awaitThenCallReal(inv, t2Committed);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).saveAndFlush(org.mockito.ArgumentMatchers.any(Deposit.class));

        AtomicReference<Throwable> t1Err = new AtomicReference<>();
        AtomicReference<Throwable> t2Err = new AtomicReference<>();

        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread t1 = new Thread(() -> {
            try {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
                diagnosticRequestService.cancelRequest(requestId, userId);
            } catch (Throwable t) {
                t1Err.set(t);
            }
        }, "unfreeze-thread");
        Thread t2 = new Thread(() -> {
            try {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
                depositService.withdraw(userId, WITHDRAW_AMOUNT, "D5M-WITHDRAW-" + System.nanoTime());
            } catch (Throwable t) {
                t2Err.set(t);
            } finally {
                t2Committed.countDown();
            }
        }, "withdraw-thread");

        t1.start();
        t2.start();
        t1.join(JOIN_TIMEOUT_MS);
        t2.join(JOIN_TIMEOUT_MS);
        assertThat(t1.isAlive()).as("unfreeze-thread must have finished - no deadlock").isFalse();
        assertThat(t2.isAlive()).as("withdraw-thread must have finished - no deadlock").isFalse();

        // --- T2 must have succeeded cleanly: it holds no stale state, nothing races it. ---
        assertThat(t2Err.get()).as("withdraw must not fail in this interleaving").isNull();

        // --- 1/2: the conflict must still genuinely occur, and be caught INSIDE
        // unfreezeForDiagnosticRequest (not merely surface unhandled at the outer commit). Since
        // the fix makes the write synchronous, this interleaving deterministically produces a real
        // conflict every repetition - assert it unconditionally, not defensively.
        assertThat(t1Err.get())
                .as("the forced interleaving must produce a real @Version conflict, caught and rethrown " +
                        "by unfreezeForDiagnosticRequest's own existing catch block - never an " +
                        "UnexpectedRollbackException (that would mean a stray transaction boundary still exists)")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class);

        // --- 5/6/7/8/9: financial/state atomicity after the (now caught-and-rethrown) rollback. ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBlockedAmount())
                .as("5/6/7: cancelRequest's whole transaction rolled back - still frozen, no partial mutation")
                .isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(afterRace.getVersion())
                .as("only the competing withdraw's mutation actually committed")
                .isEqualTo(versionBeforeRace + 1);

        DiagnosticRequest requestAfter = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(requestAfter.getStatus())
                .as("5: DiagnosticRequest remains PENDING - the status change rolled back with everything else")
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.PENDING);

        Optional<SellerDiagnosticCommitment> commitmentAfter = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId);
        assertThat(commitmentAfter).as("6: the commitment must not have been deleted").isPresent();
        assertThat(commitmentAfter.get().getStatus()).isEqualTo(CommitmentStatus.FROZEN);

        List<DepositTransaction> txsAfterRace = depositRepository.findTransactionsByUserId(userId);
        assertThat(txsAfterRace)
                .as("9: no orphan UNBLOCK ledger row from the failed unfreeze attempt")
                .noneMatch(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK);
        assertThat(txsAfterRace)
                .as("8: the competing withdrawal remains committed")
                .anyMatch(t -> t.getType() == DepositTransaction.TransactionType.WITHDRAWAL
                        && t.getAmount().compareTo(WITHDRAW_AMOUNT) == 0);

        // --- 3/4/10: FailedOperationService.saveFailedOperation invoked, durable row exists, and
        // carries enough information for FinancialRecoveryService to recover the operation. ---
        List<FailedOperation> pending = failedOperationRepository.findByStatus(FailedOperation.STATUS_PENDING);
        Optional<FailedOperation> failedOp = pending.stream()
                .filter(op -> "UNFREEZE_DIAGNOSTIC_REQUEST".equals(op.getOperationType())
                        && userId.equals(op.getUserId()) && requestId.equals(op.getAuctionId()))
                .findFirst();
        assertThat(failedOp)
                .as("3/4: saveFailedOperation must have been invoked and durably committed (its own " +
                        "REQUIRES_NEW), independent of cancelRequest's rollback")
                .isPresent();
        // 10: recovery only ever needs (userId, requestId) - it re-reads the commitment's own
        // committedAmount fresh at retry time (see SellerDiagnosticCommitmentService.releaseCommitment),
        // so the FailedOperation payload's (userId, auctionId=requestId) pair is already sufficient;
        // this is confirmed below by actually running recovery from nothing but this row.
        assertThat(failedOp.get().getUserId()).isEqualTo(userId);
        assertThat(failedOp.get().getAuctionId()).isEqualTo(requestId);

        // ==================== RECOVERY ====================
        // First sweep: performRetry's own releaseCommitment/unfreezeForDiagnosticRequest calls
        // share recoverOne's own (REQUIRED) transaction - by design (see PLAN/VERIFY-M: this
        // operation deliberately does NOT get its own REQUIRES_NEW, unlike UNFREEZE_AUCTION post-
        // D4). So the immediate post-retry isStillRequired() re-check - itself a separate
        // REQUIRES_NEW read - cannot yet see the still-uncommitted delete/unfreeze, and reports
        // "still required": recoverOne schedules a backoff retry rather than marking RESOLVED,
        // even though the retry's mutation is about to durably commit when this call returns. This
        // is the SAME already-tested resilience shape as
        // FinancialRecoveryServiceIntegrationTest's "committed but not yet marked resolved" case -
        // not a defect, and not something this fix touches.
        recoveryService.recoverOne(failedOp.get().getId());

        Deposit afterFirstSweep = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterFirstSweep.getBlockedAmount())
                .as("the financial mutation already committed on the first sweep, even though the " +
                        "FailedOperation's own status bookkeeping has not caught up yet")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId))
                .as("commitment already deleted after the first sweep").isEmpty();

        // Second sweep: isStillRequired() now correctly sees the already-committed state from a
        // fresh REQUIRES_NEW read, finds nothing left to do, and marks RESOLVED without performing
        // a second mutation.
        recoveryService.recoverOne(failedOp.get().getId());

        Deposit afterRecovery = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRecovery.getBlockedAmount())
                .as("recovery successfully unfroze the commitment").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRecovery.getBalance())
                .as("balance unaffected by the recovered unfreeze - only the earlier withdrawal changed it")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT));
        assertThat(sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId))
                .as("commitment is deleted by the successful recovery retry").isEmpty();

        List<DepositTransaction> txsAfterRecovery = depositRepository.findTransactionsByUserId(userId);
        long unblockCount = txsAfterRecovery.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK).count();
        long withdrawalCount = txsAfterRecovery.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.WITHDRAWAL).count();
        assertThat(unblockCount).as("exactly one UNBLOCK - no duplicate monetary effect from recovery").isEqualTo(1L);
        assertThat(withdrawalCount).as("still exactly one WITHDRAWAL - unaffected by recovery").isEqualTo(1L);

        FailedOperation resolved = failedOperationRepository.findById(failedOp.get().getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);

        // ==================== IDEMPOTENCY: the user's natural retry path ====================
        // DiagnosticRequest is still PENDING after recovery (recovery only touches the commitment/
        // deposit, never the request's own status - see PLAN/VERIFY-M's explicit invariant
        // analysis). The system's own designed self-heal path is simply: the user retries
        // cancelling. releaseCommitment must no-op cleanly (commitment already gone) and
        // cancelRequest must complete normally, WITHOUT any second unfreeze or duplicate ledger
        // effect.
        diagnosticRequestService.cancelRequest(requestId, userId);

        DiagnosticRequest requestAfterRetry = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(requestAfterRetry.getStatus())
                .as("the retried cancelRequest finally completes the status transition")
                .isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);

        List<DepositTransaction> txsAfterRetry = depositRepository.findTransactionsByUserId(userId);
        long unblockCountAfterRetry = txsAfterRetry.stream()
                .filter(t -> t.getType() == DepositTransaction.TransactionType.UNBLOCK).count();
        assertThat(unblockCountAfterRetry)
                .as("idempotency: the retry must NOT create a second UNBLOCK - releaseCommitment no-ops " +
                        "cleanly since the commitment no longer exists")
                .isEqualTo(1L);
        Deposit afterRetry = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRetry.getBlockedAmount())
                .as("idempotency: no second unfreeze effect on blockedAmount").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRetry.getBalance())
                .as("idempotency: no duplicate monetary effect on balance")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT));
    }

    @RepeatedTest(5)
    @DisplayName("baseline (no race): cancelRequest's unfreeze succeeds cleanly and needs no recovery")
    void cancelRequestUnfreeze_NoConcurrency_SucceedsCleanly() {
        Long userId = newUserWithDeposit();
        Long requestId = newPendingDiagnosticRequestWithFrozenCommitment(userId);

        diagnosticRequestService.cancelRequest(requestId, userId);

        Deposit after = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(after.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.getBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId)).isEmpty();
        DiagnosticRequest reloaded = diagnosticRequestRepository.findById(requestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
    }
}
