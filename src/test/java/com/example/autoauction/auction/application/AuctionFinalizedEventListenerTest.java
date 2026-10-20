// auction/application/AuctionFinalizedEventListenerTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.notification.application.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * MVP-003: pure-unit coverage of {@link AuctionFinalizedEventListener}'s own logic - which side
 * effects fire under which conditions (winner vs. no-winner), and that a failure in any one of
 * them never propagates out of {@code onAuctionFinalized} and never prevents the other,
 * independent side effects in the same invocation from still being attempted. This class does
 * NOT prove transaction semantics (AFTER_COMMIT timing, genuine rollback) - that is
 * {@link AuctionFinalizedEventAfterCommitPgIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты AuctionFinalizedEventListener")
class AuctionFinalizedEventListenerTest {

    @Mock
    private AuctionWebSocketController webSocketController;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuctionSellerResolver auctionSellerResolver;   // NOTIF-01: seller unknown unless a test says otherwise

    @InjectMocks
    private AuctionFinalizedEventListener listener;

    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long winnerId = 100L;
    private final Long loserId = 101L;
    private final BigDecimal winningBid = new BigDecimal("1200000");

    private Auction auction;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        auction = new Auction(
                auctionId, new BigDecimal("1000000"), new BigDecimal("1200000"), new BigDecimal("1500000"),
                new BigDecimal("10000"), now.minusHours(2), now.minusMinutes(5), sellerId
        );
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setVehicleInfo("Toyota Camry 2020");
    }

    @Nested
    @DisplayName("Какие side-эффекты срабатывают")
    class SideEffectDispatch {

        @Test
        @DisplayName("Победитель есть: YOU_WON, AUCTION_WON, AUCTION_LOST лузерам, AUCTION_COMPLETED продавцу, sendAuctionEnded(winner), AUCTION_FINALIZED")
        void winnerBranch_SendsAllExpectedSideEffects() {
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            listener.onAuctionFinalized(event);

            verify(webSocketController, times(1)).sendPersonalUpdate(winnerId, auction, "YOU_WON");
            verify(notificationService, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(notificationService, times(1)).sendAfterCommit(eq(loserId), eq("AUCTION_LOST"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, winnerId, winningBid);
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
            // no-winner-only call must never fire in the winner branch
            verify(webSocketController, never()).sendAuctionEnded(auctionId, null, null);
        }

        @Test
        @DisplayName("Победителя нет: только sendAuctionEnded(null,null) и AUCTION_FINALIZED, никаких уведомлений участникам")
        void noWinnerBranch_SendsOnlyAuctionEndedAndFinalizedUpdate() {
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, null, null, List.of(winnerId, loserId));

            listener.onAuctionFinalized(event);

            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, null, null);
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
            verifyNoMoreInteractions(webSocketController);
            verify(notificationService, never()).sendAfterCommit(any(), anyString(), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("Победитель без других участников: AUCTION_LOST не отправляется никому")
        void winnerBranch_NoOtherParticipants_NoLostNotifications() {
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId));

            listener.onAuctionFinalized(event);

            verify(notificationService, never()).sendAfterCommit(any(), eq("AUCTION_LOST"), anyString(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Изоляция ошибок (AFTER_COMMIT - ничего не должно \"всплыть\" наружу)")
    class ExceptionIsolation {

        @Test
        @DisplayName("Падение sendPersonalUpdate не всплывает и не мешает остальным side-эффектам")
        void personalUpdateFailure_DoesNotPropagate_OthersStillRun() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendPersonalUpdate(any(), any(), anyString());
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        }

        @Test
        @DisplayName("Падение AUCTION_WON не всплывает и не мешает AUCTION_LOST/AUCTION_COMPLETED")
        void auctionWonNotificationFailure_DoesNotPropagate_OthersStillRun() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .sendAfterCommit(eq(winnerId), eq("AUCTION_WON"), anyString(), anyString(), any(), anyString());
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).sendAfterCommit(eq(loserId), eq("AUCTION_LOST"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("Падение AUCTION_LOST одному участнику не всплывает и не мешает остальным лузерам/продавцу")
        void auctionLostNotificationFailure_DoesNotPropagate_OthersStillRun() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .sendAfterCommit(eq(loserId), eq("AUCTION_LOST"), anyString(), anyString(), any(), anyString());
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, winnerId, winningBid);
        }

        @Test
        @DisplayName("Падение AUCTION_COMPLETED не всплывает и не мешает sendAuctionEnded")
        void auctionCompletedNotificationFailure_DoesNotPropagate_OthersStillRun() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"), anyString(), anyString(), any(), anyString());
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, winnerId, winningBid);
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        }

        @Test
        @DisplayName("Падение sendAuctionEnded (winner) не всплывает и не мешает финальному AUCTION_FINALIZED update")
        void sendAuctionEndedFailure_WinnerBranch_DoesNotPropagate_FinalUpdateStillSent() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendAuctionEnded(auctionId, winnerId, winningBid);
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        }

        @Test
        @DisplayName("Падение sendAuctionEnded (no-winner) не всплывает и не мешает финальному AUCTION_FINALIZED update")
        void sendAuctionEndedFailure_NoWinnerBranch_DoesNotPropagate_FinalUpdateStillSent() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendAuctionEnded(auctionId, null, null);
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, null, null, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        }

        @Test
        @DisplayName("Падение финального sendAuctionUpdate не всплывает")
        void finalUpdateFailure_DoesNotPropagate() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendAuctionUpdate(auction, "AUCTION_FINALIZED");
            AuctionFinalizedEvent event = new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId));

            assertThatCode(() -> listener.onAuctionFinalized(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, winnerId, winningBid);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("NOTIF-01: the vehicle owner gets AUCTION_COMPLETED, the creating admin still gets it, once each")
    void completed_goesToSellerAndCreator() {
        Long owner = 20L;
        org.mockito.Mockito.when(auctionSellerResolver.resolveSellerId(auction)).thenReturn(java.util.Optional.of(owner));

        listener.onAuctionFinalized(new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId, loserId)));

        verify(notificationService, times(1)).sendAfterCommit(eq(owner), eq("AUCTION_COMPLETED"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("NOTIF-01: owner and creator are the same person -> one AUCTION_COMPLETED")
    void completed_sameOwnerAndCreator_once() {
        org.mockito.Mockito.when(auctionSellerResolver.resolveSellerId(auction)).thenReturn(java.util.Optional.of(sellerId));

        listener.onAuctionFinalized(new AuctionFinalizedEvent(auction, winnerId, winningBid, List.of(winnerId)));

        verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
    }
}
