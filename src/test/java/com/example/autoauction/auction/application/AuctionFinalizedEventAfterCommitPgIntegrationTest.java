// auction/application/AuctionFinalizedEventAfterCommitPgIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.deposit.application.DepositService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MVP-003 ADVERSARIAL PROOF: {@link AuctionFinalizedEventListener}'s {@code AFTER_COMMIT}
 * dispatch is the sole, exactly-once side-effect path for {@code AuctionFinalizer.finalizeAuction},
 * and a genuinely rolled-back finalization (not merely a Mockito-simulated one) never reaches it -
 * reusing the SAME single-threaded stale-version technique already established and proven by
 * {@link com.example.autoauction.auction.infrastructure.scheduler.AuctionFinalizationSchedulerTransactionIsolationIntegrationTest}
 * (a stale in-memory {@code Auction} copy + an independent, already-committed concurrent write
 * that bumps its real {@code @Version} - no multi-threading needed, unlike D5-R/MVP-001/002,
 * since this defect does not require a race to reproduce, only genuine staleness). That existing
 * test proves the financial/state convergence; this test proves the WS/notification consequence
 * of the same genuine conflict is now exactly-once, not leaked.
 *
 * Deliberately extends {@link AbstractPostgresIntegrationTest} WITHOUT any class-level
 * {@code @Transactional} and calls {@code auctionFinalizer.finalizeAuction(...)} with no
 * surrounding transaction template, for the same reason documented on
 * {@code AuctionFinalizationSchedulerTransactionIsolationIntegrationTest} and on this
 * codebase's other two {@code *AfterCommitPgIntegrationTest} classes: an {@code AFTER_COMMIT}
 * listener never fires inside a test-managed rollback-only transaction.
 */
@DisplayName("MVP-003: AuctionFinalizedEvent AFTER_COMMIT — exactly-once side effects, and a genuine rollback suppresses them")
class AuctionFinalizedEventAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private AuctionParticipantRepository participantRepository;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @SpyBean private AuctionWebSocketController webSocketControllerSpy;
    @SpyBean private NotificationService notificationServiceSpy;

    private Long sellerId;
    private TransactionTemplate txTemplate;

    private void initTxTemplateAndSeller() {
        txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        Role userRole = txTemplate.execute(status -> roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь"))));
        sellerId = txTemplate.execute(status -> userRepository.save(
                new User(null, "mvp3seller" + System.nanoTime(), "mvp3seller" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId());
    }

    /** Mirrors AuctionFinalizationSchedulerTransactionIsolationIntegrationTest's own fixture exactly. */
    private Long createExpiredAuctionWithWinnerAndLoser(Long winnerId, Long loserId, BigDecimal requiredDeposit) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(3), now.minusMinutes(5), sellerId
            );
            auction.setRequiredDepositAmount(requiredDeposit);
            auction.setStatus(AuctionStatus.ACTIVE);
            auction.setCurrentPrice(new BigDecimal("1050000"));
            auction.setTotalBids(3);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(new BigDecimal("1050000"));
            Auction saved = auctionRepository.save(auction);

            depositService.freezeForAuction(winnerId, saved.getId(), requiredDeposit);
            depositService.freezeForAuction(loserId, saved.getId(), requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), winnerId, requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), loserId, requiredDeposit);
            return saved.getId();
        });
    }

    private Long newUserWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "mvp3u" + System.nanoTime(), "mvp3u" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    // ======================================================================
    // Test A: successful finalization — exactly one of each expected side effect (winner branch).
    // ======================================================================

    @Test
    @DisplayName("Test A: a successful finalizeAuction sends exactly one of each expected side effect")
    void successfulFinalization_SideEffectsFireExactlyOnce() {
        initTxTemplateAndSeller();
        Long winnerId = newUserWithDeposit(new BigDecimal("50000"));
        Long loserId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createExpiredAuctionWithWinnerAndLoser(winnerId, loserId, new BigDecimal("5000.00"));
        clearInvocations(webSocketControllerSpy, notificationServiceSpy);

        boolean result = auctionFinalizer.finalizeAuction(auctionRepository.findById(auctionId).orElseThrow());

        assertThat(result).isTrue();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(persisted.getWinnerId()).isEqualTo(winnerId);

        verify(webSocketControllerSpy, times(1)).sendPersonalUpdate(eq(winnerId), any(Auction.class), eq("YOU_WON"));
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(loserId), eq("AUCTION_LOST"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(webSocketControllerSpy, times(1)).sendAuctionEnded(eq(auctionId), eq(winnerId), any(BigDecimal.class));
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("AUCTION_FINALIZED"));
    }

    // ======================================================================
    // Test B: a genuine optimistic-lock conflict (stale Auction copy vs. an independent,
    // already-committed concurrent write) must suppress ALL WS/notification side effects, even
    // though finalizeAuction ran to completion and published the event before the conflict was
    // discovered at its own deferred pre-commit flush. The subsequent legitimate retry must then
    // fire every side effect exactly once.
    // ======================================================================

    @Test
    @DisplayName("Test B: a genuine optimistic-lock rollback suppresses side effects; the legitimate retry then fires them exactly once")
    void genuineRollback_SuppressesSideEffects_RetrySucceedsExactlyOnce() {
        initTxTemplateAndSeller();
        Long winnerId = newUserWithDeposit(new BigDecimal("50000"));
        Long loserId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createExpiredAuctionWithWinnerAndLoser(winnerId, loserId, new BigDecimal("5000.00"));

        // Stale in-memory copy, then an independent, already-committed concurrent write bumping
        // the real @Version - same technique as AuctionFinalizationSchedulerTransactionIsolationIntegrationTest.
        Auction stale = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        txTemplate.execute(status -> {
            Auction fresh = auctionRepository.findById(auctionId).orElseThrow();
            fresh.setConfirmedStart(true);
            return auctionRepository.save(fresh);
        });

        clearInvocations(webSocketControllerSpy, notificationServiceSpy);

        assertThatThrownBy(() -> auctionFinalizer.finalizeAuction(stale))
                .as("finalizeAuction has no @Retryable - the genuine version conflict must propagate, not be silently swallowed");

        // --- fresh read, strictly after the failing call has already thrown: proves a REAL rollback ---
        Auction afterFailedAttempt = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterFailedAttempt.getStatus())
                .as("the WAITING_FOR_PAYMENT transition must have been rolled back")
                .isNotEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        // --- THE MVP-003 PROOF: the event WAS published (finalizeAuction ran to completion
        // before the deferred flush discovered the conflict), but because the transaction rolled
        // back, AFTER_COMMIT must never have fired. Scoped to AuctionFinalizedEventListener's own
        // side effects specifically - not a blanket verifyNoInteractions - because
        // markWinnerSafely/unfreezeDepositSafely's own REQUIRES_NEW deposit notifications
        // (DEPOSIT_UNFROZEN etc.) legitimately fire and commit independently, before the conflict
        // is discovered. ---
        verifyNoInteractions(webSocketControllerSpy);
        verify(notificationServiceSpy, never()).sendAfterCommit(any(), eq("AUCTION_WON"), anyString(), anyString(), any(), anyString());
        verify(notificationServiceSpy, never()).sendAfterCommit(any(), eq("AUCTION_LOST"), anyString(), anyString(), any(), anyString());
        verify(notificationServiceSpy, never()).sendAfterCommit(any(), eq("AUCTION_COMPLETED"), anyString(), anyString(), any(), anyString());

        // --- legitimate retry, exactly as the next scheduler tick would (fresh read) ---
        clearInvocations(webSocketControllerSpy, notificationServiceSpy);
        Auction retryCopy = auctionRepository.findById(auctionId).orElseThrow();
        boolean retrySucceeded = auctionFinalizer.finalizeAuction(retryCopy);

        assertThat(retrySucceeded).as("retry must succeed once no concurrent writer is racing it").isTrue();
        Auction afterRetry = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        verify(webSocketControllerSpy, times(1)).sendPersonalUpdate(eq(winnerId), any(Auction.class), eq("YOU_WON"));
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(loserId), eq("AUCTION_LOST"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("AUCTION_FINALIZED"));
    }

    // ======================================================================
    // Test C / Test D: a WebSocket/notification failure AFTER a successful commit must not
    // surface as a finalizeAuction failure.
    // ======================================================================

    @Test
    @DisplayName("Test C: a WebSocket failure after a successful commit does not fail finalizeAuction or affect persisted state")
    void webSocketFailureAfterCommit_DoesNotFailFinalizationOrAffectPersistedState() {
        initTxTemplateAndSeller();
        Long winnerId = newUserWithDeposit(new BigDecimal("50000"));
        Long loserId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createExpiredAuctionWithWinnerAndLoser(winnerId, loserId, new BigDecimal("5000.00"));

        doThrow(new RuntimeException("simulated WebSocket failure"))
                .when(webSocketControllerSpy).sendPersonalUpdate(eq(winnerId), any(Auction.class), eq("YOU_WON"));

        java.util.concurrent.atomic.AtomicReference<Boolean> result = new java.util.concurrent.atomic.AtomicReference<>();
        assertThatCode(() -> result.set(
                auctionFinalizer.finalizeAuction(auctionRepository.findById(auctionId).orElseThrow())))
                .as("a post-commit WebSocket failure must not propagate out of finalizeAuction")
                .doesNotThrowAnyException();

        assertThat(result.get()).isTrue();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).as("the finalization remains committed despite the WS failure")
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        // independent side effects must still have been attempted
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("AUCTION_FINALIZED"));
    }

    @Test
    @DisplayName("Test D: a notification failure after a successful commit does not fail finalizeAuction or affect persisted state")
    void notificationFailureAfterCommit_DoesNotFailFinalizationOrAffectPersistedState() {
        initTxTemplateAndSeller();
        Long winnerId = newUserWithDeposit(new BigDecimal("50000"));
        Long loserId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createExpiredAuctionWithWinnerAndLoser(winnerId, loserId, new BigDecimal("5000.00"));

        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationServiceSpy).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                        anyString(), anyString(), eq(auctionId), eq("AUCTION"));

        java.util.concurrent.atomic.AtomicReference<Boolean> result = new java.util.concurrent.atomic.AtomicReference<>();
        assertThatCode(() -> result.set(
                auctionFinalizer.finalizeAuction(auctionRepository.findById(auctionId).orElseThrow())))
                .as("a post-commit notification failure must not propagate out of finalizeAuction")
                .doesNotThrowAnyException();

        assertThat(result.get()).isTrue();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).as("the finalization remains committed despite the notification failure")
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        // independent side effects must still have been attempted
        verify(notificationServiceSpy, times(1)).sendAfterCommit(eq(loserId), eq("AUCTION_LOST"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(webSocketControllerSpy, times(1)).sendAuctionUpdate(any(Auction.class), eq("AUCTION_FINALIZED"));
    }
}
