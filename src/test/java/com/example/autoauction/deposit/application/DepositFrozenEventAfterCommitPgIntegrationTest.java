// deposit/application/DepositFrozenEventAfterCommitPgIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.notification.application.NotificationService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * G1 ADVERSARIAL PROOF: {@link DepositFrozenEventListener}'s {@code AFTER_COMMIT} dispatch is the
 * sole, exactly-once side-effect path for {@code DepositService.freezeForAuction}, and a
 * genuinely rolled-back/retried freeze (not merely a Mockito-simulated one) never leaks or
 * duplicates the DEPOSIT_FROZEN notification.
 *
 * Test C reuses the SAME real-optimistic-conflict racing technique already proven by
 * {@code BidServiceRetryConcurrencyPgIntegrationTest} (D5-R) - the exact mechanism the G1 audit
 * itself is built on - rather than duplicating a new concurrency harness. Test E reconstructs
 * buyNow's own internal sequence (freeze, then a later genuine auction-@Version conflict, both in
 * one transaction) by calling DepositService.freezeForAuction and
 * AuctionFinalizer.finalizeWithBuyNow directly rather than through BidService.buyNow() itself:
 * buyNow() fetches its auction via a pessimistic {@code findByIdWithLock}, which serializes
 * access to that row for the whole transaction and therefore cannot itself be raced into a
 * genuine @Version conflict the way an unlocked scheduler-style stale-copy read can (the same
 * stale-copy technique already established by
 * {@code AuctionFinalizedEventAfterCommitPgIntegrationTest}/{@code AuctionCancelledEventAfterCommitPgIntegrationTest}).
 * This does not alter BuyNow's implementation - it reproduces the same transactional shape
 * buyNow() itself already has (freeze, then finalizeWithBuyNow, sharing one transaction).
 *
 * Deliberately extends {@link AbstractPostgresIntegrationTest} WITHOUT any class-level
 * {@code @Transactional}: an {@code AFTER_COMMIT} listener never fires inside a test-managed
 * rollback-only transaction.
 */
@DisplayName("G1: DepositFrozenEvent AFTER_COMMIT — exactly-once side effect, and a genuine rollback/retry suppresses or deduplicates it")
class DepositFrozenEventAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DepositService depositService;
    @Autowired private BidService bidService;
    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @SpyBean private DepositRepository depositRepositorySpy;
    @SpyBean private NotificationService notificationServiceSpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal REQUIRED_DEPOSIT = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("20000.00");
    private static final BigDecimal STARTING_PRICE = new BigDecimal("100000.00");
    private static final BigDecimal MIN_BID_STEP = new BigDecimal("1000.00");
    private static final BigDecimal BID_AMOUNT = new BigDecimal("105000.00");
    private static final BigDecimal BUY_NOW_PRICE = new BigDecimal("150000.00");

    private static final long WAIT_SECONDS = 8;
    private static final long JOIN_TIMEOUT_MS = 20_000;

    private TransactionTemplate txTemplate() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return tx;
    }

    private Long newUserWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "g1u" + System.nanoTime(), "g1u" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    private Long newTrustedUserWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "g1t" + System.nanoTime(), "g1t" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    private Long newSeller() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "g1s" + System.nanoTime(), "g1s" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    /** Bare auction row, satisfying deposit_frozen.auction_id's real FK - status/content irrelevant. */
    private Long newBareAuction() {
        OffsetDateTime now = OffsetDateTime.now();
        Long sellerId = newSeller();
        Auction auction = new Auction(
                Math.abs(System.nanoTime() % 1_000_000L),
                STARTING_PRICE, null, null, MIN_BID_STEP,
                now.minusHours(1), now.plusDays(7), sellerId);
        auction.setStatus(AuctionStatus.ACTIVE);
        return auctionRepository.save(auction).getId();
    }

    private Long newActiveAuctionForBid(BigDecimal requiredDeposit) {
        OffsetDateTime now = OffsetDateTime.now();
        Long sellerId = newSeller();
        Auction auction = new Auction(
                Math.abs(System.nanoTime() % 1_000_000L),
                STARTING_PRICE, null, null, MIN_BID_STEP,
                now.minusHours(1), now.plusDays(7), sellerId);
        auction.setRequiredDepositAmount(requiredDeposit);
        auction.setStatus(AuctionStatus.ACTIVE);
        return auctionRepository.save(auction).getId();
    }

    private Long newBuyNowEligibleAuction(BigDecimal requiredDeposit) {
        OffsetDateTime now = OffsetDateTime.now();
        Long sellerId = newSeller();
        Auction auction = new Auction(
                Math.abs(System.nanoTime() % 1_000_000L),
                STARTING_PRICE, null, BUY_NOW_PRICE, MIN_BID_STEP,
                now.minusHours(1), now.plusDays(7), sellerId);
        auction.setRequiredDepositAmount(requiredDeposit);
        auction.setStatus(AuctionStatus.CREATED);
        return auctionRepository.save(auction).getId();
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("G1 synchronization latch timed out - forced interleaving failed to occur");
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
    // Test A: successful freeze — exactly one DEPOSIT_FROZEN, deposit actually frozen.
    // ======================================================================

    @Test
    @DisplayName("Test A: a successful freeze sends exactly one DEPOSIT_FROZEN notification")
    void successfulFreeze_SendsExactlyOneNotification() {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBareAuction();
        clearInvocations(notificationServiceSpy);

        depositService.freezeForAuction(userId, auctionId, REQUIRED_DEPOSIT);

        Deposit persisted = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(persisted.getBlockedAmount()).isEqualByComparingTo(REQUIRED_DEPOSIT);
        assertThat(depositRepository.isFrozenForAuction(persisted.getId(), auctionId)).isTrue();

        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(userId), eq("DEPOSIT_FROZEN"), anyString(), anyString());
    }

    // ======================================================================
    // Test B: a transaction that publishes DepositFrozenEvent and then rolls back for any reason
    // must never dispatch the notification.
    // ======================================================================

    @Test
    @DisplayName("Test B: a rolled-back transaction that published DepositFrozenEvent never dispatches the notification")
    void rollbackAfterPublish_NoNotificationDispatched() {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBareAuction();
        clearInvocations(notificationServiceSpy);

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            depositService.freezeForAuction(userId, auctionId, REQUIRED_DEPOSIT);
            throw new RuntimeException("forced rollback after publish");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("forced rollback");

        Deposit afterRollback = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRollback.getBlockedAmount())
                .as("the freeze itself must have been rolled back too")
                .isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(notificationServiceSpy);
    }

    // ======================================================================
    // Test C: BidService.placeBid retry/concurrency — reuses the exact D5-R racing technique.
    // Failed attempt = 0 notifications, successful retry = exactly 1, total = exactly 1.
    // ======================================================================

    @RepeatedTest(3)
    @DisplayName("Test C: placeBid's genuine optimistic-lock retry sends exactly one DEPOSIT_FROZEN across both attempts")
    void placeBidGenuineRetry_SendsDepositFrozenExactlyOnce() throws InterruptedException {
        Long bidderId = newTrustedUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newActiveAuctionForBid(REQUIRED_DEPOSIT);
        String withdrawKey = "G1-WITHDRAW-" + System.nanoTime();
        clearInvocations(depositRepositorySpy, notificationServiceSpy);

        CountDownLatch bidReadsDone = new CountDownLatch(2);
        CountDownLatch withdrawCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("bid-thread")) {
                Object result = inv.callRealMethod();
                bidReadsDone.countDown();
                return result;
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(bidderId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                return awaitThenCallReal(inv, bidReadsDone);
            }
            return awaitThenCallReal(inv, withdrawCommitted);
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

        assertThat(withdrawErr.get()).as("withdraw must succeed - nothing contends with it").isNull();
        assertThat(bidErr.get()).as("placeBid must ultimately succeed via its retried attempt").isNull();

        // Positive proof a genuine retry actually happened (mirrors D5-R's own evidence style).
        verify(depositRepositorySpy, times(2))
                .freezeForAuction(any(Long.class), eq(auctionId), any(BigDecimal.class), any());

        // --- THE G1 PROOF: exactly one DEPOSIT_FROZEN, never two, despite two full freeze attempts. ---
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(bidderId), eq("DEPOSIT_FROZEN"), anyString(), anyString());
    }

    // ======================================================================
    // Test D: a notification failure after a successful commit does not fail freezeForAuction or
    // affect persisted state.
    // ======================================================================

    @Test
    @DisplayName("Test D: a notification failure after a successful commit does not fail freezeForAuction or affect persisted state")
    void notificationFailureAfterCommit_DoesNotFailFreezeOrAffectPersistedState() {
        Long userId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBareAuction();

        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationServiceSpy).sendAfterCommit(eq(userId), eq("DEPOSIT_FROZEN"), anyString(), anyString());

        org.assertj.core.api.Assertions.assertThatCode(
                        () -> depositService.freezeForAuction(userId, auctionId, REQUIRED_DEPOSIT))
                .as("a post-commit notification failure must not propagate out of freezeForAuction")
                .doesNotThrowAnyException();

        Deposit persisted = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(persisted.getBlockedAmount())
                .as("the freeze remains committed despite the notification failure")
                .isEqualByComparingTo(REQUIRED_DEPOSIT);
    }

    // ======================================================================
    // Test E: buyNow's own internal sequence (freeze, then a later genuine auction-@Version
    // conflict in finalizeWithBuyNow, sharing one transaction) must roll back the freeze AND
    // suppress the DEPOSIT_FROZEN notification. Reconstructed via direct calls (see class
    // Javadoc for why buyNow() itself cannot be raced this way) - BuyNow's implementation is not
    // altered.
    // ======================================================================

    @Test
    @DisplayName("Test E: a genuine auction-version conflict after freezeForAuction (buyNow's own shape) rolls back the freeze and suppresses the notification")
    void buyNowShapedRollbackAfterFreeze_SuppressesNotification() {
        Long buyerId = newUserWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBuyNowEligibleAuction(REQUIRED_DEPOSIT);

        // Stale in-memory copy, then an independent, already-committed concurrent write bumping
        // the real @Version - same technique as AuctionFinalizedEventAfterCommitPgIntegrationTest/
        // AuctionCancelledEventAfterCommitPgIntegrationTest.
        Auction stale = txTemplate().execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        txTemplate().execute(status -> {
            Auction fresh = auctionRepository.findById(auctionId).orElseThrow();
            fresh.setConfirmedStart(true);
            return auctionRepository.save(fresh);
        });

        clearInvocations(notificationServiceSpy);

        // Reconstructs buyNow()'s own internal sequence in one shared transaction: freeze first
        // (publishes DepositFrozenEvent mid-transaction), then finalizeWithBuyNow on the STALE
        // auction copy - its own final auctionRepository.save() genuinely conflicts at the
        // deferred pre-commit flush, rolling back this whole transaction, including the freeze.
        assertThatThrownBy(() -> txTemplate().execute(status -> {
            depositService.freezeForAuction(buyerId, auctionId, REQUIRED_DEPOSIT);
            auctionFinalizer.finalizeWithBuyNow(stale, buyerId, BUY_NOW_PRICE);
            return null;
        }));

        Deposit afterRollback = depositRepository.findByUserId(buyerId).orElseThrow();
        assertThat(afterRollback.getBlockedAmount())
                .as("the freeze must have been rolled back along with finalizeWithBuyNow's own conflict")
                .isEqualByComparingTo(BigDecimal.ZERO);
        Auction afterRollbackAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterRollbackAuction.getStatus())
                .as("the Buy Now transition must not have applied")
                .isEqualTo(AuctionStatus.CREATED);

        verify(notificationServiceSpy, never()).sendAfterCommit(eq(buyerId), eq("DEPOSIT_FROZEN"), anyString(), anyString());
    }
}
