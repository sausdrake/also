// auction/application/AuctionCancelledEventListenerTest.java
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

/**
 * MVP-004: pure-unit coverage of {@link AuctionCancelledEventListener}'s own logic - that it
 * sends exactly the same AUCTION_CANCELLED notifications and WS update that used to live inline
 * in {@code AuctionFinalizer.cancelAuction}, and that a failure in any one of them never
 * propagates out of {@code onAuctionCancelled} and never prevents the other, independent side
 * effects (including OTHER participants' notifications) from still being attempted. This class
 * does NOT prove transaction semantics - that is
 * {@link AuctionCancelledEventAfterCommitPgIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты AuctionCancelledEventListener")
class AuctionCancelledEventListenerTest {

    @Mock
    private AuctionWebSocketController webSocketController;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AuctionCancelledEventListener listener;

    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long participant1 = 100L;
    private final Long participant2 = 101L;

    private Auction auction;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        auction = new Auction(
                auctionId, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                now.minusHours(1), now.plusDays(7), sellerId
        );
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.CANCELLED);
        auction.setVehicleInfo("Toyota Camry 2020");
    }

    @Nested
    @DisplayName("Какие side-эффекты срабатывают")
    class SideEffectDispatch {

        @Test
        @DisplayName("Успешная отмена: sendAuctionEnded один раз, AUCTION_CANCELLED каждому участнику")
        void successfulDispatch_SendsAuctionEndedAndNotifiesEachParticipant() {
            AuctionCancelledEvent event = new AuctionCancelledEvent(auction, List.of(participant1, participant2));

            listener.onAuctionCancelled(event);

            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, null, null);
            verify(notificationService, times(1)).send(eq(participant1), eq("AUCTION_CANCELLED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(notificationService, times(1)).send(eq(participant2), eq("AUCTION_CANCELLED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("Пустой список участников: sendAuctionEnded всё равно отправляется, уведомлений нет")
        void emptyParticipantList_SendsAuctionEndedOnly_NoNotifications() {
            AuctionCancelledEvent event = new AuctionCancelledEvent(auction, List.of());

            listener.onAuctionCancelled(event);

            verify(webSocketController, times(1)).sendAuctionEnded(auctionId, null, null);
            verify(notificationService, never()).send(any(), anyString(), anyString(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Изоляция ошибок (AFTER_COMMIT - ничего не должно \"всплыть\" наружу)")
    class ExceptionIsolation {

        @Test
        @DisplayName("Падение sendAuctionEnded не всплывает и не мешает уведомлениям участников")
        void webSocketFailure_DoesNotPropagate_ParticipantNotificationsStillSent() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendAuctionEnded(auctionId, null, null);
            AuctionCancelledEvent event = new AuctionCancelledEvent(auction, List.of(participant1, participant2));

            assertThatCode(() -> listener.onAuctionCancelled(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).send(eq(participant1), eq("AUCTION_CANCELLED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(notificationService, times(1)).send(eq(participant2), eq("AUCTION_CANCELLED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("Падение уведомления одному участнику не всплывает и не мешает уведомлению другого")
        void oneParticipantNotificationFailure_DoesNotPropagate_OtherParticipantStillNotified() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .send(eq(participant1), eq("AUCTION_CANCELLED"), anyString(), anyString(), any(), anyString());
            AuctionCancelledEvent event = new AuctionCancelledEvent(auction, List.of(participant1, participant2));

            assertThatCode(() -> listener.onAuctionCancelled(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).send(eq(participant2), eq("AUCTION_CANCELLED"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }
    }
}
