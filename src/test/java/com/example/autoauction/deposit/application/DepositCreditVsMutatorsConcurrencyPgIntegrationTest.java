package com.example.autoauction.deposit.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.common.application.FinancialRecoveryService;
import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.application.PaymentConfirmationOrchestrator;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
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
 * D4: real-PostgreSQL proof of the actual (not assumed) semantics between
 * {@link DepositService#creditFromPayment} (pessimistic {@code SELECT ... FOR UPDATE} via D3) and
 * the three Deposit-row-mutating operations confirmed by D4 Phase 1 recon to genuinely race against
 * it on the SAME row: {@link DepositService#freezeForAuction}, {@link DepositService#unfreezeForAuction}
 * and {@link DepositService#confiscateToPlatform}. {@code markAsWinner} is deliberately excluded -
 * recon proved it never mutates the {@code Deposit} entity (only a bulk update on the separate
 * {@code deposit_frozen} table), so it structurally cannot participate in an {@code @Version} race.
 *
 * <p>Each of the three mutators shares the same shape: a plain unlocked {@code findByUserId} read
 * followed, much later, by a version-checked {@code save(Deposit)}. Under PostgreSQL MVCC a plain
 * read is never blocked by another transaction's {@code SELECT ... FOR UPDATE} lock, so which side
 * "wins" is decided entirely by commit order of the two UPDATEs, not read order. Both meaningful
 * orderings are forced deterministically - not left to chance - using a {@code @SpyBean} around the
 * real, Testcontainers-backed {@link DepositRepository} (same mechanism already proven safe in
 * {@code DepositCreditPrimitivePgRollbackIntegrationTest}: the spy only ever gates timing via
 * {@link CountDownLatch}, it never fakes or replaces behaviour - every real SQL statement still
 * executes against real PostgreSQL).
 */
@DisplayName("D4: creditFromPayment vs freeze/unfreeze/confiscate — real-PostgreSQL concurrency proof")
class DepositCreditVsMutatorsConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private FailedOperationRepository failedOperationRepository;
    @Autowired private FinancialRecoveryService recoveryService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("300.00");
    private static final BigDecimal FREEZE_AMOUNT = new BigDecimal("400.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;

    private Long newUserWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d4user" + System.nanoTime(), "d4user" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    /** deposit_frozen.auction_id carries a real FK to auctions(id) (V4) - a synthetic id fails on real Postgres. */
    private Long newAuctionId() {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusHours(1), 1L);
        return auctionRepository.save(auction).getId();
    }

    private String newSucceedingPayment(Long userId, BigDecimal amount) {
        String ref = "PAY-D4-" + System.nanoTime();
        String providerRef = "PSP-D4-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, amount, null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, amount, "KGS", "TXN-D4-" + System.nanoTime()));
        return ref;
    }

    private long ledgerCount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type)
                .count();
    }

    private BigDecimal ledgerAmount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type)
                .findFirst().orElseThrow().getAmount();
    }

    /** Awaits a latch (bounded) then delegates to the real method - never fakes behaviour, only gates timing. */
    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D4 test synchronization latch timed out - forced ordering failed to occur");
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
    // SCENARIO A: creditFromPayment vs freezeForAuction
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("A1: credit commits before freeze's update -> freeze loses the optimistic race, " +
            "throws uncaught (no catch/retry path exists for freezeForAuction), rolls back completely")
    void creditVsFreeze_CreditCommitsFirst_FreezeLosesRace_NoPartialMutation() throws InterruptedException {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long initialVersion = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        Long auctionId = newAuctionId();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch creditLockAcquired = new CountDownLatch(1);
        CountDownLatch creditCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditLockAcquired.countDown();
            return result;
        }).when(depositRepositorySpy).findByUserIdForUpdate(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("freeze-thread")) {
                return awaitThenCallReal(inv, creditLockAcquired);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("freeze-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            Object result = inv.callRealMethod();
            creditCommitted.countDown();
            return result;
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> freezeErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "freeze-thread", () -> { try { depositService.freezeForAuction(userId, auctionId, FREEZE_AMOUNT); } catch (Throwable t) { freezeErr.set(t); } }
        );

        assertThat(creditErr.get()).as("credit must succeed unconditionally - it holds the pessimistic lock").isNull();
        assertThat(freezeErr.get())
                .as("freeze must lose the optimistic race and the exception must propagate uncaught - " +
                        "freezeForAuction has no try/catch and no FinancialRecoveryService retry path")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        Deposit fresh = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(fresh.getBalance()).as("only the credit applied").isEqualByComparingTo("1300.00");
        assertThat(fresh.getBlockedAmount()).as("freeze rolled back completely - blockedAmount untouched").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fresh.getVersion()).as("exactly one successful mutation (credit only)").isEqualTo(initialVersion + 1);

        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId))
                .as("freeze's FrozenDeposit INSERT rolled back with the rest of its transaction").isFalse();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.BLOCK))
                .as("freeze's ledger insert rolled back too - same physical transaction as its failed save()").isEqualTo(0);
    }

    @RepeatedTest(5)
    @DisplayName("A2: freeze commits before credit acquires its lock -> both succeed, correct combined final state")
    void creditVsFreeze_FreezeCommitsFirst_BothSucceed() throws InterruptedException {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long initialVersion = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        Long auctionId = newAuctionId();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch freezeCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, freezeCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(userId);

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> freezeErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "freeze-thread", () -> {
                    try { depositService.freezeForAuction(userId, auctionId, FREEZE_AMOUNT); }
                    catch (Throwable t) { freezeErr.set(t); }
                    finally { freezeCommitted.countDown(); }
                }
        );

        assertThat(freezeErr.get()).as("freeze must succeed - it commits before credit even acquires its lock").isNull();
        assertThat(creditErr.get()).as("credit always re-locks fresh state - never conflicts").isNull();

        Deposit fresh = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(fresh.getBalance()).isEqualByComparingTo("1300.00");
        assertThat(fresh.getBlockedAmount()).isEqualByComparingTo("400.00");
        assertThat(fresh.getVersion()).as("two successful mutations").isEqualTo(initialVersion + 2);

        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId)).isTrue();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.BLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.BLOCK)).isEqualByComparingTo("400.00");
    }

    // ======================================================================
    // SCENARIO B: creditFromPayment vs unfreezeForAuction (REQUIRES_NEW + swallow + retry)
    // ======================================================================

    /**
     * D4 DEFECT FIX (verification, PLAN/VERIFY #3, Option B): {@code DepositService.unfreezeForAuction}
     * is now a plain, non-transactional orchestrator that delegates the isolated mutation to
     * {@link DepositUnfreezeTransactionService#unfreeze} - a genuinely separate bean, called through
     * Spring's ordinary proxied dependency reference rather than a same-bean {@code this.method()}
     * call. That collaborator still writes via {@code depositRepository.saveAndFlush(deposit)}, so a
     * genuine {@code @Version} conflict is still detected synchronously - but now, if the resulting
     * commit failure (an {@code ObjectOptimisticLockingFailureException} or, per the JPA spec, an
     * {@code UnexpectedRollbackException} from THAT bean's own proxy) surfaces, it does so as part of
     * the single, ordinary Java call {@code unfreezeForAuction} makes to the collaborator - fully
     * inside {@code unfreezeForAuction}'s own try/catch, since that method has no transaction of its
     * own for the failure to interfere with. This test proves the FULL intended lifecycle against
     * real PostgreSQL/Spring/Hibernate: conflict -> caught -> FailedOperation recorded independently
     * (its own REQUIRES_NEW) -> the mutation transaction rolls back atomically (no partial mutation,
     * FrozenDeposit still present) -> the original unfreezeForAuction call returns to its caller with
     * NO exception at all -> FinancialRecoveryService discovers and retries -> retry succeeds ->
     * exactly one UNBLOCK, no duplicate PAYMENT_CREDIT -> FailedOperation RESOLVED.
     */
    @RepeatedTest(5)
    @DisplayName("B (FIXED): credit commits before unfreeze's update -> conflict is caught inside " +
            "unfreezeForAuction, FailedOperation is recorded, recovery retries it to completion with no duplicates")
    void creditVsUnfreeze_ConflictCaughtInsideMethod_RecordedThenRecoveredWithNoDuplicates() throws InterruptedException {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newAuctionId();
        depositService.freezeForAuction(userId, auctionId, FREEZE_AMOUNT);
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();

        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch creditLockAcquired = new CountDownLatch(1);
        CountDownLatch creditCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditLockAcquired.countDown();
            return result;
        }).when(depositRepositorySpy).findByUserIdForUpdate(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("unfreeze-thread")) {
                return awaitThenCallReal(inv, creditLockAcquired);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(userId);

        // credit-thread still calls plain save(); unfreeze-thread now calls saveAndFlush() (the fix).
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditCommitted.countDown();
            return result;
        }).when(depositRepositorySpy).save(any(Deposit.class));

        doAnswer(inv -> awaitThenCallReal(inv, creditCommitted))
                .when(depositRepositorySpy).saveAndFlush(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> unfreezeErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "unfreeze-thread", () -> { try { depositService.unfreezeForAuction(userId, auctionId); } catch (Throwable t) { unfreezeErr.set(t); } }
        );

        assertThat(creditErr.get()).isNull();

        // --- D: commit-boundary observation - recorded explicitly, not assumed away ---
        // Option B (separate DepositUnfreezeTransactionService bean) resolves the remaining gap
        // found in PLAN/VERIFY #2: unfreezeForAuction itself carries NO @Transactional, so there is
        // no transactional proxy around IT whose commit() could throw UnexpectedRollbackException
        // after its catch block runs. Whatever the mutation's own REQUIRES_NEW transaction does
        // (including a possible UnexpectedRollbackException from ITS OWN proxy) surfaces as part of
        // the single, ordinary call unfreezeForAuction makes to that other bean - fully inside this
        // method's own try/catch. This assertion must therefore hold unconditionally now.
        assertThat(unfreezeErr.get())
                .as("COMMIT-BOUNDARY CONTRACT CHECK: the intended contract is swallow-and-record - " +
                        "unfreezeForAuction's own catch block must be the ONLY place this conflict is ever " +
                        "observed. If this assertion fails, some transaction-level exception (e.g. " +
                        "UnexpectedRollbackException) is still escaping to the caller - that is a REMAINING " +
                        "contract defect, not a passing test, and must be reported rather than tolerated.")
                .isNull();

        // --- A: failed attempt - atomic rollback, no partial mutation ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance()).as("credit applied").isEqualByComparingTo("1300.00");
        assertThat(afterRace.getBlockedAmount())
                .as("unfreeze's whole transaction rolled back atomically - still frozen, no partial mutation")
                .isEqualByComparingTo("400.00");
        assertThat(afterRace.getVersion()).as("only credit's mutation actually committed").isEqualTo(versionBeforeRace + 1);
        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId))
                .as("FrozenDeposit DELETE rolled back with the rest of unfreeze's transaction - still present")
                .isTrue();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK))
                .as("no orphan ledger row from the failed unfreeze attempt").isEqualTo(0);

        // --- B: FailedOperation recorded independently despite TX_UNFREEZE's rollback ---
        List<FailedOperation> pending = failedOperationRepository.findByStatus(FailedOperation.STATUS_PENDING);
        FailedOperation failedOp = pending.stream()
                .filter(op -> "UNFREEZE_AUCTION".equals(op.getOperationType())
                        && userId.equals(op.getUserId()) && auctionId.equals(op.getAuctionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "FIX VERIFICATION FAILED: expected a PENDING UNFREEZE_AUCTION FailedOperation - " +
                        "the conflict was supposed to be caught inside unfreezeForAuction and recorded via " +
                        "FailedOperationService's independent REQUIRES_NEW transaction"));

        // --- C: recovery discovers and retries it to completion ---
        recoveryService.recoverOne(failedOp.getId());

        Deposit afterRecovery = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRecovery.getBalance()).as("balance unaffected by the retried unfreeze").isEqualByComparingTo("1300.00");
        assertThat(afterRecovery.getBlockedAmount()).as("retry succeeded - now unfrozen").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId))
                .as("FrozenDeposit removed by the successful retry").isFalse();

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT))
                .as("no duplicate PAYMENT_CREDIT from the retry").isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK))
                .as("exactly one UNBLOCK - the failed attempt produced none, the retry produced exactly one").isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualByComparingTo("400.00");

        FailedOperation resolved = failedOperationRepository.findById(failedOp.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(FailedOperation.STATUS_RESOLVED);
    }

    // ======================================================================
    // SCENARIO C: creditFromPayment vs confiscateToPlatform
    // ======================================================================

    private Long setUpWinnerFrozenDeposit(Long userId, Long auctionId) {
        depositService.freezeForAuction(userId, auctionId, FREEZE_AMOUNT);
        depositService.markAsWinner(userId, auctionId);
        return depositRepository.findByUserId(userId).orElseThrow().getId();
    }

    @RepeatedTest(5)
    @DisplayName("C1: credit commits before confiscate's update -> confiscate loses the race, throws uncaught " +
            "(no catch/retry path exists for confiscateToPlatform), rolls back completely")
    void creditVsConfiscate_CreditCommitsFirst_ConfiscateLosesRace_NoPartialMutation() throws InterruptedException {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newAuctionId();
        Long depositId = setUpWinnerFrozenDeposit(userId, auctionId);
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch creditLockAcquired = new CountDownLatch(1);
        CountDownLatch creditCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditLockAcquired.countDown();
            return result;
        }).when(depositRepositorySpy).findByUserIdForUpdate(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("confiscate-thread")) {
                return awaitThenCallReal(inv, creditLockAcquired);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("confiscate-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            Object result = inv.callRealMethod();
            creditCommitted.countDown();
            return result;
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> confiscateErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "confiscate-thread", () -> { try { depositService.confiscateToPlatform(auctionId, userId); } catch (Throwable t) { confiscateErr.set(t); } }
        );

        assertThat(creditErr.get()).isNull();
        assertThat(confiscateErr.get())
                .as("confiscateToPlatform has no try/catch and no retry path - conflict must propagate uncaught")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Deposit fresh = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(fresh.getBalance()).as("only the credit applied").isEqualByComparingTo("1300.00");
        assertThat(fresh.getBlockedAmount()).as("confiscation rolled back - still frozen").isEqualByComparingTo("400.00");
        assertThat(fresh.getVersion()).isEqualTo(versionBeforeRace + 1);

        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId))
                .as("confiscate's DELETE of the FrozenDeposit row rolled back too").isTrue();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.CHARGE))
                .as("confiscation's ledger insert rolled back with the rest of its transaction").isEqualTo(0);
    }

    @RepeatedTest(5)
    @DisplayName("C2: confiscate commits before credit acquires its lock -> both succeed, correct combined final state")
    void creditVsConfiscate_ConfiscateCommitsFirst_BothSucceed() throws InterruptedException {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newAuctionId();
        Long depositId = setUpWinnerFrozenDeposit(userId, auctionId);
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch confiscateCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, confiscateCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(userId);

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> confiscateErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "confiscate-thread", () -> {
                    try { depositService.confiscateToPlatform(auctionId, userId); }
                    catch (Throwable t) { confiscateErr.set(t); }
                    finally { confiscateCommitted.countDown(); }
                }
        );

        assertThat(confiscateErr.get()).isNull();
        assertThat(creditErr.get()).isNull();

        Deposit fresh = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(fresh.getBalance()).as("confiscation removes 400, credit adds 300: 1000-400+300").isEqualByComparingTo("900.00");
        assertThat(fresh.getBlockedAmount()).as("confiscation clears the frozen amount, credit never touches blockedAmount").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fresh.getVersion()).isEqualTo(versionBeforeRace + 2);

        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId)).isFalse();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.CHARGE)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.CHARGE)).isEqualByComparingTo("400.00");
    }
}
