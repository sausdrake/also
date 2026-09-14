// deposit/application/FreezeVsWithdrawConcurrencyPgIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FrozenDeposit;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-H: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#freezeForAuction} and {@link DepositService#withdraw} - the first D5
 * pairing in this series where NEITHER side holds a pessimistic lock. Both are unlocked
 * {@code findByUserId} reads followed by a plain, deferred {@code save()}, relying entirely on
 * JPA's automatic {@code @Version}-checked {@code UPDATE ... WHERE id=? AND version=?} plus
 * PostgreSQL's own row-level UPDATE serialization for correctness - confirmed by direct source
 * read during D5-H AUDIT/RECON, not assumed.
 *
 * <p>Freeze is driven through {@link BidService#buyNow} - the real production entry point,
 * deliberately NOT {@code placeBid}, since {@code placeBid} wraps {@code freezeForAuction} in a
 * {@code catch (Exception e) { throw new IllegalArgumentException(...); }} that would convert the
 * real {@link ObjectOptimisticLockingFailureException} into a different type before this test
 * could observe it - a separate, already-flagged reliability defect (retry-defeat) that is
 * explicitly OUT OF SCOPE for this test. {@code buyNow} has no such wrapping: a genuine conflict
 * from {@code freezeForAuction} propagates raw and uncaught, exactly what this test needs to
 * observe the real exception.
 *
 * <p>Withdraw is driven through {@link DepositService#withdraw} directly - it is already its own
 * outermost transaction boundary ({@code DepositController} adds none), the same precedent used
 * by every prior D5 test.
 *
 * <p>Synchronization is via a {@code @SpyBean} around the real, Testcontainers-backed
 * {@link DepositRepository}, gating only TIMING via {@link CountDownLatch} - never faking
 * behaviour or throwing a manufactured exception. {@code buyNow} calls
 * {@code depositRepository.findByUserId(userId)} TWICE for the same user in one invocation
 * (once via {@code canParticipateInAuction}, once inside {@code freezeForAuction} itself) -
 * confirmed by direct source read - so gates keyed on the freeze side's reads use a
 * {@code CountDownLatch(2)} where both calls must complete, never a {@code CountDownLatch(1)}
 * that would only account for one of them.
 */
@DisplayName("D5-H: freezeForAuction vs withdraw — real-PostgreSQL concurrency proof")
class FreezeVsWithdrawConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private BidService bidService;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal FREEZE_AMOUNT = new BigDecimal("30000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("25000.00");
    private static final BigDecimal FREEZE_AMOUNT_C = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT_C = new BigDecimal("10000.00");
    private static final long WAIT_SECONDS = 8;
    private static final long JOIN_TIMEOUT_MS = 20_000;

    private Long newTrustedBuyerWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5h" + System.nanoTime(), "d5h" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    /**
     * auctions.vehicle_id / created_by carry no FK (confirmed via migration read in AUDIT/RECON) -
     * a synthetic seller id and vehicle id are legitimate here. Status stays CREATED (the
     * constructor's default) - buyNow only requires CREATED, confirmed by the existing
     * AuctionFlowIntegrationTest.buyNow_BeforeAuctionStart_Success precedent.
     */
    private Long newAuctionForFreeze(BigDecimal requiredDeposit) {
        OffsetDateTime now = OffsetDateTime.now();
        Long sellerId = 900_000L + (System.nanoTime() % 100_000L);
        Auction auction = new Auction(
                Math.abs(System.nanoTime() % 1_000_000L),
                new BigDecimal("100000.00"), null, new BigDecimal("500000.00"),
                new BigDecimal("1000.00"),
                now.minusHours(1), now.plusDays(7),
                sellerId);
        auction.setRequiredDepositAmount(requiredDeposit);
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

    private List<FrozenDeposit> frozenDeposits(Long depositId) {
        return depositRepository.getFrozenDepositsByDepositId(depositId);
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D5-H synchronization latch timed out - forced interleaving failed to occur");
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
    // SCENARIO A — freeze wins: withdraw's stale write is rejected, its whole transaction
    // (including the WITHDRAWAL ledger row) rolls back atomically.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("A: freeze (via buyNow) commits first -> withdraw's stale write is rejected by the real " +
            "@Version check, and its whole transaction rolls back atomically")
    void freezeWins_WithdrawStaleWriteRejected() throws InterruptedException {
        Long buyerId = newTrustedBuyerWithDeposit();
        Long auctionId = newAuctionForFreeze(FREEZE_AMOUNT);
        Long depositId = depositRepository.findByUserId(buyerId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(buyerId).orElseThrow().getVersion();
        String withdrawKey = "D5H-WITHDRAW-" + System.nanoTime();

        CountDownLatch withdrawReadDone = new CountDownLatch(1);
        CountDownLatch freezeCommitted = new CountDownLatch(1);

        // withdraw-thread's own read proceeds immediately and signals; freeze-thread's reads
        // (both calls inside buyNow) wait for it - guaranteeing withdraw has already read the
        // pre-race Deposit state before freeze's own mutating read (and therefore its write)
        // can even begin.
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                Object result = inv.callRealMethod();
                withdrawReadDone.countDown();
                return result;
            }
            return awaitThenCallReal(inv, withdrawReadDone);
        }).when(depositRepositorySpy).findByUserId(buyerId);

        // withdraw-thread's save waits for freeze's full commit (buyNow returning); freeze-thread's
        // own save proceeds normally.
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                return awaitThenCallReal(inv, freezeCommitted);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> freezeErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "freeze-thread", () -> {
                    try { bidService.buyNow(auctionId, buyerId); }
                    catch (Throwable t) { freezeErr.set(t); }
                    finally { freezeCommitted.countDown(); }
                },
                "withdraw-thread", () -> {
                    try { depositService.withdraw(buyerId, WITHDRAW_AMOUNT, withdrawKey); }
                    catch (Throwable t) { withdrawErr.set(t); }
                }
        );

        assertThat(freezeErr.get()).as("freeze (buyNow) must succeed - nothing has committed against it yet").isNull();
        assertThat(withdrawErr.get())
                .as("withdraw's stale write must be rejected by the real @Version check - the genuine " +
                        "Spring-translated optimistic-lock exception, never fabricated")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // --- fresh reads only ---
        Deposit afterRace = depositRepository.findByUserId(buyerId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("NO LOST WITHDRAWAL EFFECT NEEDED: withdraw never applied, balance untouched")
                .isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(afterRace.getBlockedAmount())
                .as("freeze's mutation is the only one that committed")
                .isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getAvailableBalance()).isNotNegative();
        assertThat(afterRace.getBalance()).isNotNegative();
        assertThat(afterRace.getBlockedAmount()).isNotNegative();
        assertThat(afterRace.getVersion()).as("only freeze's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        List<FrozenDeposit> frozen = frozenDeposits(depositId);
        assertThat(frozen).as("exactly one FrozenDeposit for the winning freeze").hasSize(1);
        assertThat(frozen.get(0).getAuctionId()).isEqualTo(auctionId);
        assertThat(frozen.get(0).getAmount()).isEqualByComparingTo(FREEZE_AMOUNT);

        assertThat(ledgerCount(buyerId, DepositTransaction.TransactionType.BLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(buyerId, DepositTransaction.TransactionType.BLOCK)).isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(ledgerCount(buyerId, DepositTransaction.TransactionType.WITHDRAWAL))
                .as("NO ORPHAN LEDGER ENTRY: withdraw's failed transaction left no ledger row").isEqualTo(0);
    }

    // ======================================================================
    // SCENARIO B — withdraw wins: freeze's stale write is rejected, its whole transaction
    // (including the FrozenDeposit INSERT and BLOCK ledger row) rolls back atomically.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("B: withdraw commits first -> freeze's (buyNow's) stale write is rejected by the real " +
            "@Version check, and its whole transaction rolls back atomically")
    void withdrawWins_FreezeStaleWriteRejected() throws InterruptedException {
        Long buyerId = newTrustedBuyerWithDeposit();
        Long auctionId = newAuctionForFreeze(FREEZE_AMOUNT);
        Long depositId = depositRepository.findByUserId(buyerId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(buyerId).orElseThrow().getVersion();
        String withdrawKey = "D5H-WITHDRAW-" + System.nanoTime();

        // buyNow calls findByUserId(buyerId) TWICE (canParticipateInAuction, then
        // freezeForAuction's own read) - confirmed by direct source read. Both must complete
        // before withdraw's own read is allowed to proceed, so freeze's mutating snapshot is
        // guaranteed to predate withdraw's commit.
        CountDownLatch freezeReadsDone = new CountDownLatch(2);
        CountDownLatch withdrawCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("freeze-thread")) {
                Object result = inv.callRealMethod();
                freezeReadsDone.countDown();
                return result;
            }
            return awaitThenCallReal(inv, freezeReadsDone);
        }).when(depositRepositorySpy).findByUserId(buyerId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("freeze-thread")) {
                return awaitThenCallReal(inv, withdrawCommitted);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> freezeErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "freeze-thread", () -> { try { bidService.buyNow(auctionId, buyerId); } catch (Throwable t) { freezeErr.set(t); } },
                "withdraw-thread", () -> {
                    try { depositService.withdraw(buyerId, WITHDRAW_AMOUNT, withdrawKey); }
                    catch (Throwable t) { withdrawErr.set(t); }
                    finally { withdrawCommitted.countDown(); }
                }
        );

        assertThat(withdrawErr.get()).as("withdraw must succeed - nothing has committed against it yet").isNull();
        assertThat(freezeErr.get())
                .as("freeze's (buyNow's) stale write must be rejected by the real @Version check - " +
                        "buyNow has no catch around freezeForAuction, so the genuine exception propagates raw")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Deposit afterRace = depositRepository.findByUserId(buyerId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("only withdraw's mutation committed")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("freeze never applied - blockedAmount untouched")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getAvailableBalance()).isNotNegative();
        assertThat(afterRace.getBalance()).isNotNegative();
        assertThat(afterRace.getBlockedAmount()).isNotNegative();
        assertThat(afterRace.getVersion()).as("only withdraw's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        assertThat(frozenDeposits(depositId))
                .as("NO PARTIAL FREEZE: the failed freeze's FrozenDeposit INSERT rolled back with the rest " +
                        "of its transaction").isEmpty();

        assertThat(ledgerCount(buyerId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
        assertThat(ledgerAmount(buyerId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualByComparingTo(WITHDRAW_AMOUNT);
        assertThat(ledgerCount(buyerId, DepositTransaction.TransactionType.BLOCK))
                .as("NO ORPHAN LEDGER ENTRY: freeze's failed transaction left no ledger row").isEqualTo(0);
    }

    // ======================================================================
    // SCENARIO C — sequential, non-conflicting amounts: both operations legitimately succeed,
    // proving the fixtures/accounting are not intrinsically incompatible.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("C: freeze (via buyNow) commits, then withdraw reads fresh state and also succeeds -> " +
            "both mutations survive with the correct combined final state")
    void bothSucceed_SequentialNonConflictingAmounts() throws InterruptedException {
        Long buyerId = newTrustedBuyerWithDeposit();
        Long auctionId = newAuctionForFreeze(FREEZE_AMOUNT_C);
        Long depositId = depositRepository.findByUserId(buyerId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(buyerId).orElseThrow().getVersion();
        String withdrawKey = "D5H-WITHDRAW-C-" + System.nanoTime();

        CountDownLatch freezeCommitted = new CountDownLatch(1);

        // Only the withdraw-thread's read waits for freeze's full commit - freeze-thread's own
        // reads (both calls inside buyNow) must proceed unblocked, or buyNow could never reach
        // the point that signals freezeCommitted in the first place.
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                return awaitThenCallReal(inv, freezeCommitted);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(buyerId);

        AtomicReference<Throwable> freezeErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "freeze-thread", () -> {
                    try { bidService.buyNow(auctionId, buyerId); }
                    catch (Throwable t) { freezeErr.set(t); }
                    finally { freezeCommitted.countDown(); }
                },
                "withdraw-thread", () -> {
                    try { depositService.withdraw(buyerId, WITHDRAW_AMOUNT_C, withdrawKey); }
                    catch (Throwable t) { withdrawErr.set(t); }
                }
        );

        assertThat(freezeErr.get()).as("freeze must succeed - nothing contends with it").isNull();
        assertThat(withdrawErr.get()).as("withdraw must succeed - it re-reads fresh, post-freeze state").isNull();

        Deposit afterRace = depositRepository.findByUserId(buyerId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("balance reduced by withdrawal amount")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT_C));
        assertThat(afterRace.getBlockedAmount())
                .as("blockedAmount increased by freeze amount")
                .isEqualByComparingTo(FREEZE_AMOUNT_C);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getVersion()).as("two successful mutations").isEqualTo(versionBeforeRace + 2);

        List<FrozenDeposit> frozen = frozenDeposits(depositId);
        assertThat(frozen).hasSize(1);
        assertThat(frozen.get(0).getAmount()).isEqualByComparingTo(FREEZE_AMOUNT_C);

        assertThat(ledgerCount(buyerId, DepositTransaction.TransactionType.BLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(buyerId, DepositTransaction.TransactionType.BLOCK)).isEqualByComparingTo(FREEZE_AMOUNT_C);
        assertThat(ledgerCount(buyerId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
        assertThat(ledgerAmount(buyerId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualByComparingTo(WITHDRAW_AMOUNT_C);
    }
}
