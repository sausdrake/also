// auction/application/BidServiceRetryConcurrencyPgIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * D5-R ADVERSARIAL TEST: real-PostgreSQL, end-to-end proof that the D5-R production fix to
 * {@code BidService.placeBid} behaves exactly as the PLAN stage predicted - a genuine
 * {@code ObjectOptimisticLockingFailureException} on the freeze path is no longer caught/rewrapped
 * into {@code IllegalArgumentException}, so Spring Retry's {@code @Retryable} genuinely intercepts
 * it and drives a second, successful attempt in a fresh transaction/persistence context.
 *
 * <p><b>Race design.</b> {@code placeBid} is called EXACTLY ONCE per test execution, on one
 * thread ("bid-thread"). It races against a real {@link DepositService#withdraw} call on a
 * second thread ("withdraw-thread") for the SAME Deposit row. {@code DepositService.freezeForAuction}
 * issues two unlocked {@code depositRepository.findByUserId} reads (one via
 * {@code canParticipateInAuction}, one its own) BEFORE mutating the in-memory {@code Deposit}.
 *
 * <p><b>Mechanism (refined empirically during this test's own adversarial verification, correcting
 * the AUDIT stage's simplified characterization):</b> the frozen-row adapter call
 * ({@code depositRepository.freezeForAuction(depositId, auctionId, ...)}, the exists-check + INSERT
 * on the UNRELATED {@code deposit_frozen} table) does NOT force an auto-flush of the dirty Deposit
 * entity - Hibernate's auto-flush-before-query optimization only flushes when the about-to-run
 * query's table space overlaps the dirty entity's, and it does not here. The real
 * {@code @Version}-checked {@code UPDATE} is only scheduled (via {@code merge()}) inside
 * {@code depositRepository.save(deposit)}, and since the in-session managed Deposit's version is
 * never refreshed, {@code merge()} itself sees no conflict either (it only compares against the
 * session's in-memory version, which still matches the stale value). The actual stale-version
 * conflict is discovered by Postgres only at the DEFERRED FLUSH Hibernate performs just before
 * physical COMMIT - i.e. at {@code placeBid}'s own {@code @Transactional} boundary, AFTER the
 * method body has already returned "normally" from Java's point of view. Spring Retry still
 * catches this correctly (its advice wraps OUTSIDE Transaction's commit call, confirmed in the
 * PLAN stage via the actual resolved spring-retry/spring-tx sources) - but no single repository
 * call can be wrapped to directly capture "attempt 1's exception", so this test proves CASE B
 * (see class-level distinction required by the adversarial spec) via two independent, positive,
 * non-probabilistic lines of evidence instead:
 * <ol>
 *   <li>exact invocation counts on the {@code @SpyBean} - {@code freezeForAuction} called exactly
 *       twice, {@code findByUserId} exactly five times, {@code save(Deposit)} exactly three times -
 *       proving two FULL {@code placeBid} attempts genuinely ran, not one attempt that simply
 *       succeeded outright (which would show half these counts).</li>
 *   <li>exact final financial state - Deposit version advanced by exactly 2 (withdraw, then
 *       attempt 2's freeze), exactly one surviving {@code FrozenDeposit} row, exactly one BLOCK
 *       ledger row, exactly one bid - proving attempt 1's effects were fully discarded (rolled
 *       back with its whole transaction) while only attempt 2's survived.</li>
 * </ol>
 * Synchronization itself gates two points, both via a {@code @SpyBean} around the real,
 * Testcontainers-backed {@link DepositRepository} (timing only, never a manufactured exception):
 * withdraw-thread's {@code save(Deposit)} waits for bid-thread's first-attempt reads to complete
 * (guaranteeing attempt 1 snapshots the PRE-withdrawal state), and bid-thread's FIRST
 * {@code save(Deposit)} call waits for withdraw's observed physical commit (signalled only after
 * {@code withdraw(...)} returns) before proceeding - guaranteeing attempt 1's eventual deferred
 * flush collides with an already-advanced DB row version. The SECOND call (Spring Retry's fresh
 * attempt, same thread) is left unblocked by construction: the gating latch is already at zero.
 */
@DisplayName("D5-R: placeBid @Retryable vs withdraw — real-PostgreSQL retry-on-conflict proof")
class BidServiceRetryConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private BidService bidService;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal REQUIRED_DEPOSIT = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("20000.00");
    private static final BigDecimal STARTING_PRICE = new BigDecimal("100000.00");
    private static final BigDecimal MIN_BID_STEP = new BigDecimal("1000.00");
    private static final BigDecimal BID_AMOUNT = new BigDecimal("105000.00");
    private static final BigDecimal INSUFFICIENT_BALANCE = new BigDecimal("100.00");

    private static final long WAIT_SECONDS = 8;
    private static final long JOIN_TIMEOUT_MS = 20_000;

    private Long newTrustedBidderWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5r" + System.nanoTime(), "d5r" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    /**
     * A real, persisted seller user - NOT a synthetic out-of-range id. placeBid (unlike buyNow)
     * sends a DB-persisted seller notification via NotificationService for every accepted bid;
     * a synthetic sellerId trips user_notifications' FK constraint, which (even though the
     * resulting exception is caught and logged inside placeBid) leaves the shared participating
     * transaction marked rollback-only - a self-inflicted test-fixture defect discovered and
     * fixed during this test's own adversarial verification, unrelated to the D5-R production fix.
     */
    private Long newSeller() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5r-seller" + System.nanoTime(), "d5r-seller" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        return userRepository.save(u).getId();
    }

    private Long newActiveAuctionForBid(BigDecimal requiredDeposit) {
        OffsetDateTime now = OffsetDateTime.now();
        Long sellerId = newSeller();
        Auction auction = new Auction(
                Math.abs(System.nanoTime() % 1_000_000L),
                STARTING_PRICE, null, null, MIN_BID_STEP,
                now.minusHours(1), now.plusDays(7),
                sellerId);
        auction.setRequiredDepositAmount(requiredDeposit);
        auction.setStatus(AuctionStatus.ACTIVE);
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
            throw new AssertionError("D5-R synchronization latch timed out - forced interleaving failed to occur");
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
    // MAIN SCENARIO — placeBid called EXACTLY ONCE. Its first internal freeze attempt genuinely
    // conflicts with a concurrently-committing withdraw; Spring Retry drives a second, successful
    // attempt in a fresh transaction. Proven with positive invocation-count evidence, not mere
    // absence of an exception from the test's point of view.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("placeBid's first freeze attempt genuinely conflicts with a concurrent withdraw -> " +
            "Spring Retry executes a second attempt in a fresh transaction, which succeeds (CASE B, " +
            "proven positively)")
    void placeBidRetriesAfterGenuineOptimisticConflict_AndSucceeds() throws InterruptedException {
        Long bidderId = newTrustedBidderWithDeposit(INITIAL_BALANCE);
        Long auctionId = newActiveAuctionForBid(REQUIRED_DEPOSIT);
        Long depositId = depositRepository.findByUserId(bidderId).orElseThrow().getId();
        Long versionBeforeRace = depositRepository.findByUserId(bidderId).orElseThrow().getVersion();
        String withdrawKey = "D5R-WITHDRAW-" + System.nanoTime();
        // fixture setup above (createDeposit, the two fixture reads) also calls findByUserId -
        // reset counts before the race/act phase so invocation-count evidence below reflects only
        // placeBid's and withdraw's own calls.
        org.mockito.Mockito.clearInvocations(depositRepositorySpy);

        // bid-thread's two unlocked reads (canParticipateInAuction + freezeForAuction's own read,
        // both inside attempt 1) must both complete before withdraw is allowed to commit - this is
        // what guarantees attempt 1 snapshots the PRE-withdrawal Deposit state.
        CountDownLatch bidReadsDone = new CountDownLatch(2);
        // withdraw must have fully, physically committed (signalled only after withdraw() returns)
        // before bid-thread's first flush-triggering freeze call is allowed to proceed.
        CountDownLatch withdrawCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("bid-thread")) {
                Object result = inv.callRealMethod();
                bidReadsDone.countDown();
                return result;
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(bidderId);

        /*
         * GATE DESIGN NOTE (corrected during this test's own adversarial verification): the
         * frozen-row adapter call (depositRepository.freezeForAuction, the exists-check + INSERT
         * on the UNRELATED deposit_frozen table) does NOT force an auto-flush of the dirty Deposit
         * entity - Hibernate's auto-flush-before-query optimization only flushes when the about-
         * to-run query's table space overlaps the dirty entity's table space, and it does not here.
         * The real @Version-checked UPDATE for Deposit is only scheduled (via merge()) inside
         * depositRepository.save(deposit) - and since the in-session managed Deposit's version was
         * never refreshed, merge() itself sees no conflict either (it only compares against the
         * SESSION's in-memory version, which still matches). The actual stale-version UPDATE is
         * therefore discovered by Postgres only at the DEFERRED FLUSH that Hibernate performs just
         * before physical COMMIT - i.e. at placeBid's own @Transactional boundary, AFTER the method
         * body has already returned "normally". Spring Retry still catches this correctly (its
         * advice wraps OUTSIDE Transaction's commit call, confirmed in the PLAN stage), but the
         * test cannot capture "attempt 1's exception" from any single repository call - the proof
         * of CASE B must instead rest on (a) exact invocation counts showing 2 full attempts
         * actually ran, and (b) exact final state showing attempt 1's effects were fully discarded
         * (rolled back) while only attempt 2's survived - both asserted below.
         */
        AtomicInteger bidSaveCallCount = new AtomicInteger(0);

        doAnswer(inv -> {
            String name = Thread.currentThread().getName();
            if (name.equals("withdraw-thread")) {
                return awaitThenCallReal(inv, bidReadsDone);
            }
            int callNum = bidSaveCallCount.incrementAndGet();
            if (callNum == 1) {
                return awaitThenCallReal(inv, withdrawCommitted);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> bidErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "bid-thread", () -> {
                    try { bidService.placeBid(auctionId, BID_AMOUNT, bidderId); }
                    catch (Throwable t) { bidErr.set(t); }
                },
                "withdraw-thread", () -> {
                    try { depositService.withdraw(bidderId, WITHDRAW_AMOUNT, withdrawKey); }
                    catch (Throwable t) { withdrawErr.set(t); }
                    finally { withdrawCommitted.countDown(); }
                }
        );

        // --- CASE B positive evidence (not merely "placeBid succeeded") ---
        assertThat(withdrawErr.get()).as("withdraw must succeed - nothing contends with it").isNull();
        assertThat(bidErr.get())
                .as("placeBid (the single call made by this test) must ultimately succeed - via its " +
                        "second, Spring-Retry-driven attempt")
                .isNull();
        // Exactly 2 full attempts reached the freeze-adapter call (one per @Retryable attempt) -
        // proves a genuine retry occurred, not that attempt 1 simply succeeded outright.
        verify(depositRepositorySpy, times(2))
                .freezeForAuction(eq(depositId), eq(auctionId), any(BigDecimal.class), any(FreezeReason.class));
        // 2 reads per placeBid attempt (canParticipateInAuction + freezeForAuction's own read) x 2
        // attempts, plus withdraw's own single unlocked read = 5.
        verify(depositRepositorySpy, times(5)).findByUserId(bidderId);
        // bid-thread's save(Deposit) x2 (one per attempt) + withdraw-thread's save(Deposit) x1 = 3.
        verify(depositRepositorySpy, times(3)).save(any(Deposit.class));

        // --- fresh reads only: exact final financial state, zero residue from attempt 1 ---
        Deposit afterRace = depositRepository.findByUserId(bidderId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("only withdraw's mutation touched balance")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("exactly one successful freeze (attempt 2) - no double-freeze from a phantom retry")
                .isEqualByComparingTo(REQUIRED_DEPOSIT);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getAvailableBalance()).isNotNegative();
        assertThat(afterRace.getVersion())
                .as("exactly two committed mutations: withdraw, then attempt 2's freeze - attempt 1's " +
                        "failed flush never advanced the version")
                .isEqualTo(versionBeforeRace + 2);

        List<FrozenDeposit> frozen = frozenDeposits(depositId);
        assertThat(frozen).as("exactly one FrozenDeposit - from attempt 2 only").hasSize(1);
        assertThat(frozen.get(0).getAuctionId()).isEqualTo(auctionId);
        assertThat(frozen.get(0).getAmount()).isEqualByComparingTo(REQUIRED_DEPOSIT);

        assertThat(ledgerCount(bidderId, DepositTransaction.TransactionType.BLOCK))
                .as("exactly one BLOCK ledger row - attempt 1's FrozenDeposit and BLOCK ledger INSERTs " +
                        "did execute, but were rolled back along with its whole transaction when the " +
                        "deferred Deposit UPDATE's stale @Version was detected at pre-commit flush; only " +
                        "attempt 2's INSERTs survive")
                .isEqualTo(1);
        assertThat(ledgerAmount(bidderId, DepositTransaction.TransactionType.BLOCK))
                .isEqualByComparingTo(REQUIRED_DEPOSIT);
        assertThat(ledgerCount(bidderId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
        assertThat(ledgerAmount(bidderId, DepositTransaction.TransactionType.WITHDRAWAL))
                .isEqualByComparingTo(WITHDRAW_AMOUNT);

        assertThat(auctionRepository.findById(auctionId).orElseThrow().getWinnerId())
                .as("exactly one bid accepted - from the attempt that actually completed the full flow")
                .isEqualTo(bidderId);
        assertThat(auctionRepository.findById(auctionId).orElseThrow().getTotalBids())
                .as("no duplicate bid from a phantom extra attempt")
                .isEqualTo(1);
    }

    // ======================================================================
    // NEGATIVE CONTROL — ordinary insufficient-funds rejection is a single attempt, never retried.
    // IllegalArgumentException is not in @Retryable's configured exception set; this proves it
    // empirically rather than merely by reading the annotation.
    // ======================================================================

    @Test
    @DisplayName("NEGATIVE CONTROL: insufficient-funds rejection is a single attempt, never retried")
    void insufficientFunds_IsNotRetried() {
        Long bidderId = newTrustedBidderWithDeposit(INSUFFICIENT_BALANCE);
        Long auctionId = newActiveAuctionForBid(REQUIRED_DEPOSIT);
        // setup (createDeposit, etc.) also calls findByUserId - reset counts before the act phase
        // so the verification below reflects only placeBid's own invocations.
        org.mockito.Mockito.clearInvocations(depositRepositorySpy);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> bidService.placeBid(auctionId, BID_AMOUNT, bidderId)))
                .as("must be rejected - deposit balance is below the auction's required amount")
                .isInstanceOf(IllegalArgumentException.class);

        // Positive evidence of "not retried": canParticipateInAuction's single findByUserId read
        // occurred exactly once - a second (retried) attempt would have produced a second read.
        verify(depositRepositorySpy, times(1)).findByUserId(bidderId);
        verify(depositRepositorySpy, never())
                .freezeForAuction(any(), any(), any(BigDecimal.class), any(FreezeReason.class));

        Deposit afterAttempt = depositRepository.findByUserId(bidderId).orElseThrow();
        assertThat(afterAttempt.getBlockedAmount())
                .as("no partial/erroneous freeze effect from the rejected attempt")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(frozenDeposits(afterAttempt.getId())).isEmpty();
        assertThat(ledgerCount(bidderId, DepositTransaction.TransactionType.BLOCK)).isEqualTo(0);
    }
}
