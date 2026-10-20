// auction/application/BidPlacedEventListenerTest.java
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MVP-001: pure-unit coverage of {@link BidPlacedEventListener}'s own logic - which side effects
 * fire under which conditions, and that a failure in any one of them never propagates out of
 * {@code onBidPlaced} (see the class Javadoc for why that isolation matters: this listener runs
 * AFTER the surrounding transaction has already committed, so an uncaught exception here cannot
 * roll anything back, only surface as a spurious failure of an already-successful bid).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты BidPlacedEventListener")
class BidPlacedEventListenerTest {

    @Mock
    private AuctionWebSocketController webSocketController;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuctionSellerResolver auctionSellerResolver;   // NOTIF-01: seller unknown unless a test says otherwise

    @InjectMocks
    private BidPlacedEventListener listener;

    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long bidderId = 100L;
    private final Long prevLeaderId = 200L;
    private final BigDecimal bidAmount = new BigDecimal("1010000");

    private Auction auction;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        auction = new Auction(
                auctionId, new BigDecimal("1000000"), new BigDecimal("1200000"), new BigDecimal("1500000"),
                new BigDecimal("10000"), now.minusHours(1), now.plusDays(7), sellerId
        );
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setVehicleInfo("Toyota Camry 2020");
    }

    @Nested
    @DisplayName("Какие side-эффекты срабатывают")
    class SideEffectDispatch {

        @Test
        @DisplayName("Продление аукциона: сначала AUCTION_EXTENDED, затем BID_PLACED")
        void extended_SendsExtendedThenUpdate() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, true);

            listener.onBidPlaced(event);

            verify(webSocketController, times(1)).sendAuctionExtended(auctionId, auction.getEndTime());
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "BID_PLACED");
        }

        @Test
        @DisplayName("Без продления: AUCTION_EXTENDED не отправляется")
        void notExtended_NoExtendedMessage() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false);

            listener.onBidPlaced(event);

            verify(webSocketController, never()).sendAuctionExtended(anyLong(), any());
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "BID_PLACED");
        }

        @Test
        @DisplayName("Есть предыдущий лидер (другой пользователь) - отправляется OUTBID")
        void prevLeaderDifferentFromBidder_SendsOutbid() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false);

            listener.onBidPlaced(event);

            verify(notificationService, times(1)).sendAfterCommit(eq(prevLeaderId), eq("OUTBID"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("Предыдущего лидера нет (null) - OUTBID не отправляется")
        void noPrevLeader_NoOutbid() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, null, false);

            listener.onBidPlaced(event);

            verify(notificationService, never()).sendAfterCommit(anyLong(), eq("OUTBID"), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("Предыдущий лидер == текущий участник - OUTBID не отправляется")
        void prevLeaderSameAsBidder_NoOutbid() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, bidderId, false);

            listener.onBidPlaced(event);

            verify(notificationService, never()).sendAfterCommit(anyLong(), eq("OUTBID"), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("Продавец отличается от участника - отправляется NEW_BID_ON_VEHICLE")
        void sellerDifferentFromBidder_SendsSellerNotification() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false);

            listener.onBidPlaced(event);

            verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("NEW_BID_ON_VEHICLE"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("AUCTION_LEADER всегда отправляется участнику, сделавшему ставку")
        void alwaysNotifiesBidderAsLeader() {
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false);

            listener.onBidPlaced(event);

            verify(notificationService, times(1)).sendAfterCommit(eq(bidderId), eq("AUCTION_LEADER"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }
    }

    @Nested
    @DisplayName("Изоляция ошибок (AFTER_COMMIT - ничего не должно \"всплыть\" наружу)")
    class ExceptionIsolation {

        @Test
        @DisplayName("Падение sendAuctionUpdate не всплывает и не мешает остальным side-эффектам")
        void webSocketUpdateFailure_DoesNotPropagate_OthersStillRun() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendAuctionUpdate(any(), anyString());
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false);

            assertThatCode(() -> listener.onBidPlaced(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).sendAfterCommit(eq(bidderId), eq("AUCTION_LEADER"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("Падение sendAuctionExtended не всплывает и не мешает sendAuctionUpdate")
        void webSocketExtendedFailure_DoesNotPropagate_UpdateStillSent() {
            doThrow(new RuntimeException("boom")).when(webSocketController).sendAuctionExtended(anyLong(), any());
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, true);

            assertThatCode(() -> listener.onBidPlaced(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "BID_PLACED");
        }

        @Test
        @DisplayName("Падение одного notificationService.send не всплывает и не мешает остальным вызовам")
        void oneNotificationFailure_DoesNotPropagate_OthersStillSent() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .sendAfterCommit(eq(prevLeaderId), eq("OUTBID"), anyString(), anyString(), any(), anyString());
            BidPlacedEvent event = new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false);

            assertThatCode(() -> listener.onBidPlaced(event)).doesNotThrowAnyException();

            verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("NEW_BID_ON_VEHICLE"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(notificationService, times(1)).sendAfterCommit(eq(bidderId), eq("AUCTION_LEADER"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("NOTIF-01: NEW_BID_ON_VEHICLE goes to the vehicle owner, not to the admin who created the auction")
    void newBid_goesToOwnerNotCreator() {
        Long owner = 20L;
        org.mockito.Mockito.when(auctionSellerResolver.resolveSellerId(auction)).thenReturn(java.util.Optional.of(owner));

        listener.onBidPlaced(new BidPlacedEvent(auction, bidderId, bidAmount, prevLeaderId, false));

        verify(notificationService, times(1)).sendAfterCommit(eq(owner), eq("NEW_BID_ON_VEHICLE"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationService, never()).sendAfterCommit(eq(sellerId), eq("NEW_BID_ON_VEHICLE"), anyString(), anyString(), any(), anyString());
    }
}
