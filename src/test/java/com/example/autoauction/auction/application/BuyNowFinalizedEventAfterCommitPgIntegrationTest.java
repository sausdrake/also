// auction/application/BuyNowFinalizedEventAfterCommitPgIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MVP-002 ADVERSARIAL PROOF: {@link BuyNowFinalizedEventListener}'s {@code AFTER_COMMIT} dispatch
 * is now the sole, exactly-once Buy Now side-effect path, and a genuinely rolled-back Buy Now
 * (not merely a Mockito-simulated one) never reaches it - using the SAME real-PostgreSQL,
 * real-optimistic-conflict technique already proven by
 * {@link BidServiceRetryConcurrencyPgIntegrationTest} (D5-R) and reused by
 * {@link BidPlacedEventAfterCommitPgIntegrationTest} for MVP-001, plus the rollback-proof idiom
 * already established by {@code SaleCommitmentResolutionRollbackIntegrationTest} (read fresh,
 * non-cached state strictly after the failing call has already returned/thrown).
 *
 * Deliberately extends {@link AbstractPostgresIntegrationTest} WITHOUT an additional class-level
 * {@code @Transactional}, for the same reason documented on
 * {@link BidPlacedEventAfterCommitPgIntegrationTest}: an {@code AFTER_COMMIT} listener never
 * fires inside a test-managed rollback-only transaction, since {@code buyNow()}'s own
 * {@code @Transactional} would merely join it and never physically commit.
 */
@DisplayName("MVP-002: BuyNowFinalizedEvent AFTER_COMMIT — exactly-once side effects, and a genuine rollback suppresses them")
class BuyNowFinalizedEventAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private BidService bidService;
    @Autowired private DepositService depositService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;
    @SpyBean private AuctionWebSocketController webSocketControllerSpy;
    @SpyBean private NotificationService notificationServiceSpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal REQUIRED_DEPOSIT = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("20000.00");
    private static final BigDecimal STARTING_PRICE = new BigDecimal("100000.00");
    private static final BigDecimal MIN_BID_STEP = new BigDecimal("1000.00");
    private static final BigDecimal BUY_NOW_PRICE = new BigDecimal("150000.00");

    private static final long WAIT_SECONDS = 8;
    private static final long JOIN_TIMEOUT_MS = 20_000;

    private Long newTrustedBuyerWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "mvp2" + System.nanoTime(), "mvp2" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    private Long newSeller() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "mvp2-seller" + System.nanoTime(), "mvp2-seller" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        return userRepository.save(u).getId();
    }

    private Long newBuyNowEligibleAuction(BigDecimal requiredDeposit) {
        OffsetDateTime now = OffsetDateTime.now();
        Long sellerId = newSeller();
        Auction auction = new Auction(
                Math.abs(System.nanoTime() % 1_000_000L),
                STARTING_PRICE, null, BUY_NOW_PRICE, MIN_BID_STEP,
                now.minusHours(1), now.plusDays(7),
                sellerId);
        auction.setRequiredDepositAmount(requiredDeposit);
        auction.setStatus(AuctionStatus.CREATED);
        return auctionRepository.save(auction).getId();
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("MVP-002 synchronization latch timed out - forced interleaving failed to occur");
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
    // Test A / Test E: successful Buy Now — exactly one of each side effect, proving both that
    // the dispatch works and that the old finalizer+BidService double-dispatch is gone.
    // ======================================================================

    @Test
    @DisplayName("Test A/E: a successful Buy Now sends exactly one AUCTION_WON notification and exactly one BUY_NOW WS update pair, never two")
    void successfulBuyNow_SideEffectsFireExactlyOnce_NoDuplicate() {
        Long buyerId = newTrustedBuyerWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBuyNowEligibleAuction(REQUIRED_DEPOSIT);

        BidResponse response = bidService.buyNow(auctionId, buyerId);

        assertThat(response).isNotNull();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(persisted.getWinnerId()).isEqualTo(buyerId);

        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(buyerId), eq("AUCTION_WON"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                eq(auctionId), eq("AUCTION"));
        verify(webSocketControllerSpy, times(1)).sendBuyNowNotification(eq(auctionId), eq(buyerId), any(BigDecimal.class));
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("BUY_NOW"));
    }

    // ======================================================================
    // Test B: a genuine rollback (real optimistic-lock conflict on the buyer's own deposit,
    // discovered at buyNow()'s own deferred pre-commit flush - i.e. AFTER finalizeWithBuyNow, and
    // therefore AFTER the BuyNowFinalizedEvent, already ran in program order) must suppress the
    // AFTER_COMMIT listener entirely. buyNow() has no @Retryable, so this exception is not
    // retried - it propagates directly to the caller, and the whole transaction rolls back.
    // ======================================================================

    @Test
    @DisplayName("Test B: a genuine rollback after the event was already published suppresses the WS/notification side effects entirely")
    void genuineRollbackAfterEventPublished_SuppressesSideEffects() throws InterruptedException {
        Long buyerId = newTrustedBuyerWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBuyNowEligibleAuction(REQUIRED_DEPOSIT);
        String withdrawKey = "MVP2-WITHDRAW-" + System.nanoTime();
        org.mockito.Mockito.clearInvocations(depositRepositorySpy, webSocketControllerSpy, notificationServiceSpy);

        CountDownLatch buyReadsDone = new CountDownLatch(2);
        CountDownLatch withdrawCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("buy-thread")) {
                Object result = inv.callRealMethod();
                buyReadsDone.countDown();
                return result;
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(buyerId);

        doAnswer(inv -> {
            String name = Thread.currentThread().getName();
            if (name.equals("withdraw-thread")) {
                return awaitThenCallReal(inv, buyReadsDone);
            }
            // buy-thread's single save(Deposit) call (buyNow has no @Retryable, so this fires
            // exactly once) - delay it until withdraw has physically committed, guaranteeing the
            // deferred pre-commit flush at buyNow()'s own transaction boundary collides with an
            // already-advanced DB row version.
            return awaitThenCallReal(inv, withdrawCommitted);
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> buyErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "buy-thread", () -> {
                    try { bidService.buyNow(auctionId, buyerId); }
                    catch (Throwable t) { buyErr.set(t); }
                },
                "withdraw-thread", () -> {
                    try { depositService.withdraw(buyerId, WITHDRAW_AMOUNT, withdrawKey); }
                    catch (Throwable t) { withdrawErr.set(t); }
                    finally { withdrawCommitted.countDown(); }
                }
        );

        assertThat(withdrawErr.get()).as("withdraw must succeed - nothing contends with it").isNull();
        assertThat(buyErr.get())
                .as("buyNow has no @Retryable - the genuine optimistic-lock conflict must propagate, not be swallowed or retried")
                .isNotNull();

        // --- fresh reads only, strictly after the failing call has already thrown: proves a REAL rollback ---
        Auction afterRollback = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterRollback.getStatus())
                .as("the auction's WAITING_FOR_PAYMENT transition must have been rolled back")
                .isEqualTo(AuctionStatus.CREATED);
        assertThat(afterRollback.getWinnerId()).as("no winner recorded - the whole sale rolled back").isNull();

        // --- THE MVP-002 PROOF: the event WAS published (finalizeWithBuyNow ran to completion
        // before the deferred flush discovered the conflict), but because the transaction rolled
        // back, AFTER_COMMIT must never have fired. Scoped to the Buy Now side effects
        // specifically - not a blanket verifyNoInteractions - because DepositService's own
        // (out-of-scope, pre-existing) DEPOSIT_FROZEN notification legitimately fires earlier in
        // this same transaction, before the conflict is discovered. ---
        verifyNoInteractions(webSocketControllerSpy);
        verify(notificationServiceSpy, org.mockito.Mockito.never()).sendAfterCommit(
                any(), eq("AUCTION_WON"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyString());
    }

    // ======================================================================
    // Test C / Test D: a WebSocket/notification failure AFTER a successful commit must not
    // surface as a buyNow() failure - see mvp-001-plan.md §2 (same AFTER_COMMIT exception-
    // propagation risk analysis applies unchanged here) and mvp-002-plan.md §6.
    // ======================================================================

    @Test
    @DisplayName("Test C: a WebSocket failure after a successful commit does not fail buyNow or affect persisted state")
    void webSocketFailureAfterCommit_DoesNotFailBuyNowOrAffectPersistedState() {
        Long buyerId = newTrustedBuyerWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBuyNowEligibleAuction(REQUIRED_DEPOSIT);

        doThrow(new RuntimeException("simulated WebSocket failure"))
                .when(webSocketControllerSpy).sendBuyNowNotification(any(), any(), any());

        AtomicReference<BidResponse> response = new AtomicReference<>();
        assertThatCode(() -> response.set(bidService.buyNow(auctionId, buyerId)))
                .as("a post-commit WebSocket failure must not propagate out of buyNow, and must not trigger a retry")
                .doesNotThrowAnyException();

        assertThat(response.get()).isNotNull();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).as("the sale remains committed despite the WS failure").isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(persisted.getWinnerId()).isEqualTo(buyerId);
    }

    @Test
    @DisplayName("Test D: a notification failure after a successful commit does not fail buyNow or affect persisted state")
    void notificationFailureAfterCommit_DoesNotFailBuyNowOrAffectPersistedState() {
        Long buyerId = newTrustedBuyerWithDeposit(INITIAL_BALANCE);
        Long auctionId = newBuyNowEligibleAuction(REQUIRED_DEPOSIT);

        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationServiceSpy).sendAfterCommit(eq(buyerId), eq("AUCTION_WON"),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        eq(auctionId), eq("AUCTION"));

        AtomicReference<BidResponse> response = new AtomicReference<>();
        assertThatCode(() -> response.set(bidService.buyNow(auctionId, buyerId)))
                .as("a post-commit notification failure must not propagate out of buyNow, and must not trigger a retry")
                .doesNotThrowAnyException();

        assertThat(response.get()).isNotNull();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).as("the sale remains committed despite the notification failure").isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(persisted.getWinnerId()).isEqualTo(buyerId);
        // The WS update itself is a separate, unrelated call in the same listener and must still
        // have gone through (matches the original code's own try-block grouping: the WS pair is
        // its own try/catch, independent of the notification's).
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("BUY_NOW"));
    }
}
