// auction/application/BidPlacedEventAfterCommitPgIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.FreezeReason;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MVP-001 ADVERSARIAL PROOF: {@link BidPlacedEventListener}'s {@code AFTER_COMMIT} dispatch
 * genuinely eliminates the duplicate-side-effect defect identified in
 * {@code .auto-dev/mvp-001-plan.md} — using the SAME real-PostgreSQL, real-optimistic-conflict
 * racing technique already established and proven reliable by
 * {@link BidServiceRetryConcurrencyPgIntegrationTest} (D5-R), not a mocked exception. That test
 * proves the retry itself (deposit call counts, final financial state); this test proves the
 * WebSocket/notification consequence of that same retry is now exactly-once, not duplicated.
 *
 * Deliberately extends {@link AbstractPostgresIntegrationTest} WITHOUT an additional class-level
 * {@code @Transactional} (unlike {@code BidServiceRetryIntegrationTest}): an {@code AFTER_COMMIT}
 * listener never fires at all inside a test-managed rollback-only transaction, since
 * {@code placeBid}'s own {@code @Transactional} would merely join that outer transaction, which
 * never physically commits. A real, physically-committing transaction is the whole point being
 * verified here.
 */
@DisplayName("MVP-001: BidPlacedEvent AFTER_COMMIT — exactly-once side effects across a genuine retry, and failure isolation")
class BidPlacedEventAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private BidService bidService;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
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
    private static final BigDecimal BID_AMOUNT = new BigDecimal("105000.00");

    private static final long WAIT_SECONDS = 8;
    private static final long JOIN_TIMEOUT_MS = 20_000;

    private Long newTrustedBidderWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "mvp1" + System.nanoTime(), "mvp1" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role), true, null, null);
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    private Long newSeller() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "mvp1-seller" + System.nanoTime(), "mvp1-seller" + System.nanoTime() + "@test.com",
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

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("MVP-001 synchronization latch timed out - forced interleaving failed to occur");
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
    // Reuses D5-R's exact racing mechanism (placeBid's first freeze attempt genuinely conflicts
    // with a concurrently-committing withdraw, forcing a real ObjectOptimisticLockingFailureException
    // discovered at pre-commit flush, driving a genuine second @Retryable attempt) - see that test's
    // class Javadoc for the full mechanism explanation. This test adds the MVP-001-specific proof on
    // top: despite two full placeBid attempts genuinely running, the WebSocket update and the
    // AUCTION_LEADER notification each fire exactly once, not twice.
    // ======================================================================

    @RepeatedTest(3)
    @DisplayName("A genuine retried bid sends exactly one WS BID_PLACED update and exactly one AUCTION_LEADER notification, never two")
    void genuineRetry_SideEffectsFireExactlyOnce() throws InterruptedException {
        Long bidderId = newTrustedBidderWithDeposit(INITIAL_BALANCE);
        Long auctionId = newActiveAuctionForBid(REQUIRED_DEPOSIT);
        String withdrawKey = "MVP1-WITHDRAW-" + System.nanoTime();
        org.mockito.Mockito.clearInvocations(depositRepositorySpy, webSocketControllerSpy, notificationServiceSpy);

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

        assertThat(withdrawErr.get()).as("withdraw must succeed - nothing contends with it").isNull();
        assertThat(bidErr.get()).as("placeBid must ultimately succeed via its retried attempt").isNull();

        // Positive proof a genuine retry actually happened (mirrors D5-R's own evidence style).
        verify(depositRepositorySpy, times(2))
                .freezeForAuction(any(Long.class), eq(auctionId), any(BigDecimal.class), any(FreezeReason.class));

        // --- THE MVP-001 PROOF: exactly one of each side effect, never two, despite two full attempts ---
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("BID_PLACED"));
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(bidderId), eq("AUCTION_LEADER"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));

        assertThat(auctionRepository.findById(auctionId).orElseThrow().getWinnerId())
                .as("exactly one bid accepted")
                .isEqualTo(bidderId);
        assertThat(auctionRepository.findById(auctionId).orElseThrow().getTotalBids())
                .as("no duplicate bid from a phantom extra attempt")
                .isEqualTo(1);
    }

    // ======================================================================
    // A WebSocket/notification failure AFTER a successful commit must not surface as a placeBid
    // failure - see mvp-001-plan.md §2 on why AFTER_COMMIT alone does not guarantee this and why
    // BidPlacedEventListener wraps each side effect individually.
    // ======================================================================

    @Test
    @DisplayName("A WebSocket failure after a successful commit does not fail placeBid or affect persisted state")
    void webSocketFailureAfterCommit_DoesNotFailPlaceBidOrAffectPersistedState() {
        Long bidderId = newTrustedBidderWithDeposit(INITIAL_BALANCE);
        Long auctionId = newActiveAuctionForBid(REQUIRED_DEPOSIT);

        doThrow(new RuntimeException("simulated WebSocket failure"))
                .when(webSocketControllerSpy).sendAuctionUpdate(any(Auction.class), eq("BID_PLACED"));

        AtomicReference<BidResponse> response = new AtomicReference<>();
        assertThatCode(() -> response.set(bidService.placeBid(auctionId, BID_AMOUNT, bidderId)))
                .as("a post-commit WebSocket failure must not propagate out of placeBid")
                .doesNotThrowAnyException();

        assertThat(response.get()).isNotNull();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getWinnerId()).as("the bid remains committed despite the WS failure").isEqualTo(bidderId);
        assertThat(persisted.getTotalBids()).isEqualTo(1);
    }

    @Test
    @DisplayName("A notification failure after a successful commit does not fail placeBid or affect persisted state")
    void notificationFailureAfterCommit_DoesNotFailPlaceBidOrAffectPersistedState() {
        Long bidderId = newTrustedBidderWithDeposit(INITIAL_BALANCE);
        Long auctionId = newActiveAuctionForBid(REQUIRED_DEPOSIT);

        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationServiceSpy).sendAfterCommit(eq(bidderId), eq("AUCTION_LEADER"),
                        anyString(), anyString(), eq(auctionId), eq("AUCTION"));

        AtomicReference<BidResponse> response = new AtomicReference<>();
        assertThatCode(() -> response.set(bidService.placeBid(auctionId, BID_AMOUNT, bidderId)))
                .as("a post-commit notification failure must not propagate out of placeBid")
                .doesNotThrowAnyException();

        assertThat(response.get()).isNotNull();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getWinnerId()).as("the bid remains committed despite the notification failure").isEqualTo(bidderId);
        assertThat(persisted.getTotalBids()).isEqualTo(1);
        // The WS update itself is a separate, unrelated call and must still have gone through.
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("BID_PLACED"));
    }
}
