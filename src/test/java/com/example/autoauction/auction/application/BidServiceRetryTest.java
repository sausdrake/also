// auction/application/BidServiceRetryTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.EnableRetry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@EnableRetry
@Disabled("@Retryable не работает с моками - требует интеграционного тестирования")
@DisplayName("Тесты @Retryable в BidService")
class BidServiceRetryTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private DepositService depositService;

    @Mock
    private AuctionParticipantRepository participantRepository;

    @Mock
    private AuctionFinalizer auctionFinalizer;

    @InjectMocks
    private BidService bidService;

    private Auction activeAuction;

    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long bidderId = 100L;
    private final BigDecimal startingPrice = new BigDecimal("1000000");
    private final BigDecimal validBid = new BigDecimal("1010000");
    private final BigDecimal minBidStep = new BigDecimal("10000");

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();

        activeAuction = new Auction(
                1L,
                startingPrice,
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                minBidStep,
                now.minusHours(1),
                now.plusDays(7),
                sellerId
        );
        activeAuction.setId(auctionId);
        activeAuction.setStatus(AuctionStatus.ACTIVE);
        activeAuction.setCurrentPrice(startingPrice);
        activeAuction.setTotalBids(0);
        activeAuction.setVersion(0L);
    }

    @Test
    @Disabled("Требует интеграционного тестирования")
    @DisplayName("Успешная ставка без конфликта - без повторных попыток")
    void placeBid_Success_NoRetry() {
        // тест временно отключён
    }

    @Test
    @Disabled("Требует интеграционного тестирования")
    @DisplayName("OptimisticLockException - ретрай и успех со второй попытки")
    void placeBid_OptimisticLockConflict_RetryAndSuccess() {
        // тест временно отключён
    }

    @Test
    @Disabled("Требует интеграционного тестирования")
    @DisplayName("OptimisticLockException - три неудачные попытки, затем исключение")
    void placeBid_OptimisticLockConflict_AllRetriesFail() {
        // тест временно отключён
    }
}