// auction/application/BuyNowFinalizedEventListenerTest.java
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

import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MVP-002: pure-unit coverage of {@link BuyNowFinalizedEventListener}'s own logic - that it sends
 * exactly the same AUCTION_WON notification and BUY_NOW WebSocket pair that used to live inline in
 * {@code AuctionFinalizer.finalizeWithBuyNow}, and that a failure in any one of them never
 * propagates out of {@code onBuyNowFinalized} (this listener runs AFTER the surrounding
 * transaction has already committed, so an uncaught exception here cannot roll anything back, only
 * surface as a spurious failure of an already-successful Buy Now sale).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты BuyNowFinalizedEventListener")
class BuyNowFinalizedEventListenerTest {

    @Mock
    private AuctionWebSocketController webSocketController;

    @Mock
    private NotificationService notificationService;

    @Mock
    private com.example.autoauction.subscription.application.LotSubscriptionService lotSubscriptionService;

    @Mock
    private AuctionSellerResolver auctionSellerResolver;   // NOTIF-01: seller unknown unless a test says otherwise

    @InjectMocks
    private BuyNowFinalizedEventListener listener;

    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long winnerId = 100L;
    private final BigDecimal buyNowAmount = new BigDecimal("1500000");

    private Auction auction;

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();
        auction = new Auction(
                auctionId, new BigDecimal("1000000"), new BigDecimal("1200000"), buyNowAmount,
                new BigDecimal("10000"), now.minusHours(1), now.plusDays(7), sellerId
        );
        auction.setId(auctionId);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setVehicleInfo("Toyota Camry 2020");
        auction.setWinnerId(winnerId);
        auction.setWinningBid(buyNowAmount);
    }

    @Nested
    @DisplayName("Какие side-эффекты срабатывают")
    class SideEffectDispatch {

        @Test
        @DisplayName("Успешный Buy Now: отправляется AUCTION_WON и WS BUY_NOW обновление")
        void sendsWinNotificationAndWebSocketUpdate() {
            BuyNowFinalizedEvent event = new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount);

            listener.onBuyNowFinalized(event);

            verify(notificationService, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
            verify(webSocketController, times(1)).sendBuyNowNotification(auctionId, winnerId, buyNowAmount);
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "BUY_NOW");
        }
    }

    @Nested
    @DisplayName("Изоляция ошибок (AFTER_COMMIT - ничего не должно \"всплыть\" наружу)")
    class ExceptionIsolation {

        @Test
        @DisplayName("Падение notificationService.send не всплывает и не мешает WebSocket-вызовам")
        void notificationFailure_DoesNotPropagate_WebSocketStillSent() {
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .sendAfterCommit(eq(winnerId), eq("AUCTION_WON"), anyString(), anyString(), any(), anyString());
            BuyNowFinalizedEvent event = new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount);

            assertThatCode(() -> listener.onBuyNowFinalized(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendBuyNowNotification(auctionId, winnerId, buyNowAmount);
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "BUY_NOW");
        }

        @Test
        @DisplayName("Падение sendBuyNowNotification не всплывает (тот же try-блок, что и в оригинале, включает sendAuctionUpdate)")
        void buyNowWebSocketFailure_DoesNotPropagate() {
            doThrow(new RuntimeException("boom")).when(webSocketController)
                    .sendBuyNowNotification(any(), any(), any());
            BuyNowFinalizedEvent event = new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount);

            assertThatCode(() -> listener.onBuyNowFinalized(event)).doesNotThrowAnyException();

            // sendBuyNowNotification throwing aborts the try block before sendAuctionUpdate in the
            // SAME try block runs (matches the original inline code's own grouping) - the point
            // being verified here is only that the exception itself never escapes the listener.
            verify(notificationService, times(1)).sendAfterCommit(eq(winnerId), eq("AUCTION_WON"),
                    anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("Падение sendAuctionUpdate не всплывает")
        void auctionUpdateWebSocketFailure_DoesNotPropagate() {
            doThrow(new RuntimeException("boom")).when(webSocketController)
                    .sendAuctionUpdate(any(), eq("BUY_NOW"));
            BuyNowFinalizedEvent event = new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount);

            assertThatCode(() -> listener.onBuyNowFinalized(event)).doesNotThrowAnyException();

            verify(webSocketController, times(1)).sendBuyNowNotification(auctionId, winnerId, buyNowAmount);
        }
    }

    @Nested
    @DisplayName("Сценарий 9.3: о покупке Buy Now узнают создатель аукциона и все, кто следит за лотом")
    class CreatorAndFollowers {

        private void sentTo(Long userId, String type, int times) {
            verify(notificationService, times(times)).sendAfterCommit(eq(userId), eq(type), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        }

        @Test
        @DisplayName("создатель получает AUCTION_COMPLETED, подписчики (кроме победителя) - AUCTION_ENDED, каждый один раз")
        void creatorAndFollowersAreNotifiedOnce() {
            org.mockito.Mockito.when(lotSubscriptionService.getSubscriberIds(auctionId))
                    .thenReturn(java.util.Arrays.asList(200L, 300L, winnerId, 200L, null));

            listener.onBuyNowFinalized(new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount));

            sentTo(sellerId, "AUCTION_COMPLETED", 1);
            sentTo(200L, "AUCTION_ENDED", 1);
            sentTo(300L, "AUCTION_ENDED", 1);
            sentTo(winnerId, "AUCTION_ENDED", 0);
            sentTo(winnerId, "AUCTION_WON", 1);
        }

        @Test
        @DisplayName("создатель, который сам следит за лотом, получает только AUCTION_COMPLETED")
        void creatorWhoFollowsGetsOneNotification() {
            org.mockito.Mockito.when(lotSubscriptionService.getSubscriberIds(auctionId)).thenReturn(java.util.List.of(sellerId));

            listener.onBuyNowFinalized(new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount));

            sentTo(sellerId, "AUCTION_COMPLETED", 1);
            sentTo(sellerId, "AUCTION_ENDED", 0);
        }

        @Test
        @DisplayName("сбой загрузки подписчиков не всплывает и не мешает остальным побочным эффектам")
        void followerLookupFailure_DoesNotPropagate() {
            doThrow(new RuntimeException("db down")).when(lotSubscriptionService).getSubscriberIds(auctionId);

            assertThatCode(() -> listener.onBuyNowFinalized(new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount)))
                    .doesNotThrowAnyException();

            sentTo(winnerId, "AUCTION_WON", 1);
            sentTo(sellerId, "AUCTION_COMPLETED", 1);
            verify(webSocketController, times(1)).sendAuctionUpdate(auction, "BUY_NOW");
        }

        @Test
        @DisplayName("сбой уведомления одного подписчика не мешает остальным")
        void oneFollowerFailure_DoesNotStopTheOthers() {
            org.mockito.Mockito.when(lotSubscriptionService.getSubscriberIds(auctionId)).thenReturn(java.util.List.of(200L, 300L));
            doThrow(new RuntimeException("boom")).when(notificationService)
                    .sendAfterCommit(eq(200L), eq("AUCTION_ENDED"), anyString(), anyString(), any(), anyString());

            assertThatCode(() -> listener.onBuyNowFinalized(new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount)))
                    .doesNotThrowAnyException();

            sentTo(300L, "AUCTION_ENDED", 1);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("NOTIF-01: Buy Now - the vehicle owner and the creating admin each get AUCTION_COMPLETED once, not AUCTION_ENDED")
    void buyNowCompleted_goesToOwnerAndCreator() {
        Long owner = 20L;
        org.mockito.Mockito.when(auctionSellerResolver.resolveSellerId(auction)).thenReturn(java.util.Optional.of(owner));
        org.mockito.Mockito.when(lotSubscriptionService.getSubscriberIds(auctionId)).thenReturn(java.util.List.of(owner, 300L));

        listener.onBuyNowFinalized(new BuyNowFinalizedEvent(auction, winnerId, buyNowAmount));

        verify(notificationService, times(1)).sendAfterCommit(eq(owner), eq("AUCTION_COMPLETED"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationService, times(1)).sendAfterCommit(eq(sellerId), eq("AUCTION_COMPLETED"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
        verify(notificationService, never()).sendAfterCommit(eq(owner), eq("AUCTION_ENDED"), anyString(), anyString(), any(), anyString());
        verify(notificationService, times(1)).sendAfterCommit(eq(300L), eq("AUCTION_ENDED"), anyString(), anyString(), eq(auctionId), eq("AUCTION"));
    }
}
