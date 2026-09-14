// deposit/application/UnfreezeAuctionVsWithdrawConcurrencyPgIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.common.application.FinancialRecoveryService;
import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-J: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#unfreezeForAuction} (post-D4: delegates to
 * {@link DepositUnfreezeTransactionService#unfreeze}, {@code REQUIRES_NEW},
 * {@code saveAndFlush} - synchronous conflict detection) and
 * {@link DepositService#withdraw} (unlocked {@code findByUserId} read, plain deferred
 * {@code save()}, {@code REQUIRED}, its own standalone top-level transaction since it is called
 * directly from {@code DepositController} with no wrapping transaction).
 *
 * <p>This exact pairing was flagged in the D5 AUDIT/RECON report as the single highest-priority
 * untested slice of D4's own fix: every existing test proving D4's swallow-and-record contract
 * (`DepositCreditVsMutatorsConcurrencyPgIntegrationTest`, Scenario B) races {@code unfreezeForAuction}
 * only against {@code creditFromPayment} - a PESSIMISTIC-lock opponent that behaves very differently
 * (it holds its lock continuously from read to write, so it can never itself lose an optimistic
 * race). {@code withdraw} is structurally different: a pure, unlocked, deferred-flush optimistic
 * writer, with no catch/swallow/recovery contract of its own - exactly the kind of opponent that,
 * per this project's own history (D4, D5-M), can hide commit-timing asymmetries that a
 * pessimistic-lock opponent cannot exercise.
 *
 * <p>Both meaningful commit orderings are forced deterministically via a {@code @SpyBean} around
 * the real, Testcontainers-backed {@link DepositRepository} - the spy only ever gates TIMING via
 * {@link CountDownLatch}, never fakes behaviour or throws a manufactured exception; every SQL
 * statement still executes for real against real PostgreSQL.
 */
@DisplayName("D5-J: unfreezeForAuction vs withdraw — real-PostgreSQL concurrency proof")
class UnfreezeAuctionVsWithdrawConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private FailedOperationRepository failedOperationRepository;
    @Autowired private FinancialRecoveryService recoveryService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal FREEZE_AMOUNT = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("15000.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5j" + System.nanoTime(), "d5j" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    /** deposit_frozen.auction_id carries a real FK to auctions(id) - a synthetic id fails on real Postgres. */
    private Long newAuctionId() {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusHours(1), 1L);
        return auctionRepository.save(auction).getId();
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
            throw new AssertionError("D5-J synchronization latch timed out - forced interleaving failed to occur");
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
    // CASE B/D: withdraw commits first -> unfreeze loses the race
    // (the critical generalization check for D4's fix against a non-pessimistic opponent)
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("withdraw commits before unfreeze's flush -> unfreeze loses cleanly (caught, recorded, " +
            "swallowed by its own D4 contract), withdraw remains committed, recovery resolves it in one sweep")
    void withdrawCommitsFirst_UnfreezeLosesRace_RecordedAndRecoveredInOneSweep() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long auctionId = newAuctionId();
        depositService.freezeForAuction(userId, auctionId, FREEZE_AMOUNT);
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();

        CountDownLatch t1ReadDone = new CountDownLatch(1);
        CountDownLatch t2Committed = new CountDownLatch(1);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("unfreeze-thread")) {
                Object result = inv.callRealMethod();
                t1ReadDone.countDown();
                return result;
            }
            return awaitThenCallReal(inv, t1ReadDone);
        }).when(depositRepositorySpy).findByUserId(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                Object result = inv.callRealMethod();
                t2Committed.countDown();
                return result;
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("unfreeze-thread")) {
                return awaitThenCallReal(inv, t2Committed);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).saveAndFlush(any(Deposit.class));

        AtomicReference<Throwable> t1Err = new AtomicReference<>();
        AtomicReference<Throwable> t2Err = new AtomicReference<>();

        runTwoThreads(
                "unfreeze-thread", () -> { try { depositService.unfreezeForAuction(userId, auctionId); } catch (Throwable t) { t1Err.set(t); } },
                "withdraw-thread", () -> { try { depositService.withdraw(userId, WITHDRAW_AMOUNT, "D5J-WITHDRAW-" + System.nanoTime()); } catch (Throwable t) { t2Err.set(t); } }
        );

        assertThat(t2Err.get()).as("withdraw must succeed cleanly in this interleaving").isNull();
        assertThat(t1Err.get())
                .as("unfreezeForAuction is void and self-swallows per its D4 contract - the caller must never observe an exception")
                .isNull();

        // --- state after the race: atomic rollback of the failed unfreeze, withdraw committed ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance()).as("only withdraw applied").isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("unfreeze's whole REQUIRES_NEW transaction rolled back atomically - still frozen")
                .isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(afterRace.getVersion()).as("only withdraw's mutation actually committed").isEqualTo(versionBeforeRace + 1);
        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId))
                .as("FrozenDeposit DELETE rolled back with the rest of unfreeze's transaction").isTrue();
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK))
                .as("no orphan UNBLOCK from the failed unfreeze attempt").isEqualTo(0);

        // --- FailedOperation recorded independently, exactly as D4 requires ---
        List<FailedOperation> pending = failedOperationRepository.findByStatus(FailedOperation.STATUS_PENDING);
        Optional<FailedOperation> failedOp = pending.stream()
                .filter(op -> "UNFREEZE_AUCTION".equals(op.getOperationType())
                        && userId.equals(op.getUserId()) && auctionId.equals(op.getAuctionId()))
                .findFirst();
        assertThat(failedOp).as("D4's REQUIRES_NEW + saveAndFlush contract must still hold against this opponent").isPresent();

        // --- recovery: unlike D5-M's diagnostic-request case, UNFREEZE_AUCTION's retry runs in its
        // own independent REQUIRES_NEW (DepositUnfreezeTransactionService) - it durably commits
        // BEFORE recoverOne's own post-retry verification read, so ONE sweep should suffice. ---
        recoveryService.recoverOne(failedOp.get().getId());

        Deposit afterRecovery = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRecovery.getBlockedAmount()).as("recovery successfully unfroze the auction hold").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId)).isFalse();

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL))
                .as("no duplicate WITHDRAWAL from recovery").isEqualTo(1);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK))
                .as("exactly one UNBLOCK - the failed attempt produced none, the retry produced exactly one").isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualByComparingTo(FREEZE_AMOUNT);

        FailedOperation resolved = failedOperationRepository.findById(failedOp.get().getId()).orElseThrow();
        assertThat(resolved.getStatus())
                .as("UNFREEZE_AUCTION's retry commits independently (REQUIRES_NEW) before recoverOne's own " +
                        "verification read, so - unlike D5-M's diagnostic-request case - ONE sweep must resolve it")
                .isEqualTo(FailedOperation.STATUS_RESOLVED);
    }

    // ======================================================================
    // CASE A/C: unfreeze commits first -> withdraw loses the race
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("unfreeze commits before withdraw's flush -> unfreeze succeeds, withdraw loses and " +
            "surfaces its own ObjectOptimisticLockingFailureException normally (no swallow, no recovery contract)")
    void unfreezeCommitsFirst_WithdrawLosesRace_SurfacesNormally() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long auctionId = newAuctionId();
        depositService.freezeForAuction(userId, auctionId, FREEZE_AMOUNT);
        Long depositId = depositRepository.findByUserId(userId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();

        CountDownLatch t1Committed = new CountDownLatch(1);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                return awaitThenCallReal(inv, t1Committed);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> t1Err = new AtomicReference<>();
        AtomicReference<Throwable> t2Err = new AtomicReference<>();

        runTwoThreads(
                "unfreeze-thread", () -> {
                    try { depositService.unfreezeForAuction(userId, auctionId); }
                    catch (Throwable t) { t1Err.set(t); }
                    finally { t1Committed.countDown(); }
                },
                "withdraw-thread", () -> { try { depositService.withdraw(userId, WITHDRAW_AMOUNT, "D5J-WITHDRAW-" + System.nanoTime()); } catch (Throwable t) { t2Err.set(t); } }
        );

        assertThat(t1Err.get()).as("unfreeze must succeed - it commits before withdraw even attempts its flush").isNull();
        assertThat(t2Err.get())
                .as("withdraw has no catch/swallow contract of its own - a genuine conflict must surface " +
                        "directly to its caller, exactly as for every other unlocked Deposit mutator")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance()).as("withdraw never applied").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(afterRace.getBlockedAmount()).as("unfreeze succeeded").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getVersion()).as("only unfreeze's mutation actually committed").isEqualTo(versionBeforeRace + 1);
        assertThat(depositRepository.isFrozenForAuction(depositId, auctionId)).isFalse();

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.UNBLOCK)).isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL))
                .as("withdraw's failed transaction left no ledger row").isEqualTo(0);

        List<FailedOperation> pending = failedOperationRepository.findByStatus(FailedOperation.STATUS_PENDING);
        boolean anyRecorded = pending.stream().anyMatch(op ->
                userId.equals(op.getUserId()) && auctionId.equals(op.getAuctionId()));
        assertThat(anyRecorded)
                .as("withdraw failures are not, and should not be, recorded via FailedOperationService - " +
                        "there is no automatic-retry contract for a user-initiated withdrawal").isFalse();
    }
}
