// auction/application/AuctionCancelledEventAfterCommitPgIntegrationTest.java
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MVP-004 ADVERSARIAL PROOF: {@link AuctionCancelledEventListener}'s {@code AFTER_COMMIT}
 * dispatch is the sole, exactly-once side-effect path for {@code AuctionFinalizer.cancelAuction},
 * and a genuinely rolled-back cancellation (not merely a Mockito-simulated one) never reaches it
 * - reusing the SAME single-threaded stale-version technique already established and proven by
 * {@link com.example.autoauction.auction.infrastructure.scheduler.AuctionCompletionSchedulerTransactionIsolationIntegrationTest}
 * (that test is NOT modified - a stale in-memory {@code Auction} copy + an independent,
 * already-committed concurrent write that bumps its real {@code @Version}). That existing test
 * proves the financial/state isolation and convergence; this test proves the WS/notification
 * consequence of the same genuine conflict is now exactly-once, not leaked.
 *
 * Deliberately extends {@link AbstractPostgresIntegrationTest} WITHOUT any class-level
 * {@code @Transactional} and calls {@code auctionFinalizer.cancelAuction(...)} with no
 * surrounding transaction template, for the same reason documented on this codebase's other
 * {@code *AfterCommitPgIntegrationTest} classes: an {@code AFTER_COMMIT} listener never fires
 * inside a test-managed rollback-only transaction.
 */
@DisplayName("MVP-004: AuctionCancelledEvent AFTER_COMMIT — exactly-once side effects, and a genuine rollback suppresses them")
class AuctionCancelledEventAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

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
                new User(null, "mvp4seller" + System.nanoTime(), "mvp4seller" + System.nanoTime() + "@test.com",
                        "encoded", true, Set.of(userRole))).getId());
    }

    private Long newUserWithDeposit(BigDecimal balance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "mvp4u" + System.nanoTime(), "mvp4u" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    /** Mirrors AuctionCompletionSchedulerTransactionIsolationIntegrationTest's own fixture exactly. */
    private Long createOverdueAuctionWithParticipant(Long participantId, BigDecimal requiredDeposit) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(
                    1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                    now.minusHours(5), now.plusHours(1), sellerId
            );
            auction.setRequiredDepositAmount(requiredDeposit);
            Auction saved = auctionRepository.save(auction);
            depositService.freezeForAuction(participantId, saved.getId(), requiredDeposit);
            participantRepository.registerParticipant(saved.getId(), participantId, requiredDeposit);
            return saved.getId();
        });
    }

    // ======================================================================
    // Test A: successful cancellation — exactly one of each expected side effect.
    // ======================================================================

    @Test
    @DisplayName("Test A: a successful cancelAuction sends exactly one sendAuctionEnded and one AUCTION_CANCELLED per participant")
    void successfulCancellation_SideEffectsFireExactlyOnce() {
        initTxTemplateAndSeller();
        Long participantId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createOverdueAuctionWithParticipant(participantId, new BigDecimal("5000.00"));
        clearInvocations(webSocketControllerSpy, notificationServiceSpy);

        boolean result = auctionFinalizer.cancelAuction(auctionRepository.findById(auctionId).orElseThrow());

        assertThat(result).isTrue();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

        verify(webSocketControllerSpy, times(1)).sendAuctionEnded(auctionId, null, null);
        verify(notificationServiceSpy, times(1)).send(eq(participantId), eq("AUCTION_CANCELLED"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
    }

    // ======================================================================
    // Test B: a genuine optimistic-lock conflict must suppress ALL WS/notification side effects,
    // even though cancelAuction ran to completion and published the event before the conflict
    // was discovered at its own deferred pre-commit flush. The subsequent legitimate retry must
    // then fire every side effect exactly once.
    // ======================================================================

    @Test
    @DisplayName("Test B: a genuine optimistic-lock rollback suppresses side effects; the legitimate retry then fires them exactly once")
    void genuineRollback_SuppressesSideEffects_RetrySucceedsExactlyOnce() {
        initTxTemplateAndSeller();
        Long participantId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createOverdueAuctionWithParticipant(participantId, new BigDecimal("5000.00"));

        // Stale in-memory copy, then an independent, already-committed concurrent write bumping
        // the real @Version - same technique as AuctionCompletionSchedulerTransactionIsolationIntegrationTest.
        Auction stale = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        txTemplate.execute(status -> {
            Auction fresh = auctionRepository.findById(auctionId).orElseThrow();
            fresh.setConfirmedStart(true);
            return auctionRepository.save(fresh);
        });

        clearInvocations(webSocketControllerSpy, notificationServiceSpy);

        assertThatThrownBy(() -> auctionFinalizer.cancelAuction(stale))
                .as("cancelAuction has no @Retryable - the genuine version conflict must propagate, not be silently swallowed");

        // --- fresh read, strictly after the failing call has already thrown: proves a REAL rollback ---
        Auction afterFailedAttempt = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterFailedAttempt.getStatus())
                .as("the CANCELLED transition must have been rolled back")
                .isNotEqualTo(AuctionStatus.CANCELLED);

        // --- THE MVP-004 PROOF: the event WAS published (cancelAuction ran to completion before
        // the deferred flush discovered the conflict), but because the transaction rolled back,
        // AFTER_COMMIT must never have fired. ---
        verifyNoInteractions(webSocketControllerSpy);
        verify(notificationServiceSpy, org.mockito.Mockito.never()).send(
                any(), eq("AUCTION_CANCELLED"), anyString(), anyString(), any(), anyString());

        // --- legitimate retry, exactly as the next scheduler tick would (fresh read) ---
        clearInvocations(webSocketControllerSpy, notificationServiceSpy);
        Auction retryCopy = auctionRepository.findById(auctionId).orElseThrow();
        boolean retrySucceeded = auctionFinalizer.cancelAuction(retryCopy);

        assertThat(retrySucceeded).as("retry must succeed once no concurrent writer is racing it").isTrue();
        Auction afterRetry = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

        verify(webSocketControllerSpy, times(1)).sendAuctionEnded(auctionId, null, null);
        verify(notificationServiceSpy, times(1)).send(eq(participantId), eq("AUCTION_CANCELLED"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
    }

    // ======================================================================
    // Test C / Test D: a WebSocket/notification failure AFTER a successful commit must not
    // surface as a cancelAuction failure.
    // ======================================================================

    @Test
    @DisplayName("Test C: a WebSocket failure after a successful commit does not fail cancelAuction or affect persisted state")
    void webSocketFailureAfterCommit_DoesNotFailCancellationOrAffectPersistedState() {
        initTxTemplateAndSeller();
        Long participantId = newUserWithDeposit(new BigDecimal("50000"));
        Long auctionId = createOverdueAuctionWithParticipant(participantId, new BigDecimal("5000.00"));

        doThrow(new RuntimeException("simulated WebSocket failure"))
                .when(webSocketControllerSpy).sendAuctionEnded(auctionId, null, null);

        AtomicReference<Boolean> result = new AtomicReference<>();
        assertThatCode(() -> result.set(
                auctionFinalizer.cancelAuction(auctionRepository.findById(auctionId).orElseThrow())))
                .as("a post-commit WebSocket failure must not propagate out of cancelAuction")
                .doesNotThrowAnyException();

        assertThat(result.get()).isTrue();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).as("the cancellation remains committed despite the WS failure")
                .isEqualTo(AuctionStatus.CANCELLED);
        verify(notificationServiceSpy, times(1)).send(eq(participantId), eq("AUCTION_CANCELLED"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
    }

    @Test
    @DisplayName("Test D: a notification failure after a successful commit does not fail cancelAuction or affect persisted state")
    void notificationFailureAfterCommit_DoesNotFailCancellationOrAffectPersistedState() {
        initTxTemplateAndSeller();
        Long participant1 = newUserWithDeposit(new BigDecimal("50000"));
        Long participant2 = newUserWithDeposit(new BigDecimal("50000"));
        BigDecimal requiredDeposit = new BigDecimal("5000.00");
        Long auctionId = createOverdueAuctionWithParticipant(participant1, requiredDeposit);
        txTemplate.execute(status -> {
            depositService.freezeForAuction(participant2, auctionId, requiredDeposit);
            participantRepository.registerParticipant(auctionId, participant2, requiredDeposit);
            return null;
        });

        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationServiceSpy).send(eq(participant1), eq("AUCTION_CANCELLED"),
                        anyString(), anyString(), eq(auctionId), eq("AUCTION"));

        AtomicReference<Boolean> result = new AtomicReference<>();
        assertThatCode(() -> result.set(
                auctionFinalizer.cancelAuction(auctionRepository.findById(auctionId).orElseThrow())))
                .as("a post-commit notification failure must not propagate out of cancelAuction")
                .doesNotThrowAnyException();

        assertThat(result.get()).isTrue();
        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).as("the cancellation remains committed despite the notification failure")
                .isEqualTo(AuctionStatus.CANCELLED);
        // independent side effects must still have been attempted
        verify(notificationServiceSpy, times(1)).send(eq(participant2), eq("AUCTION_CANCELLED"),
                anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(webSocketControllerSpy, times(1)).sendAuctionEnded(auctionId, null, null);
    }
}
