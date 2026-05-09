// auction/domain/AuctionExtensionTest.java
package com.example.autoauction.auction.domain;

import com.example.autoauction.config.AuctionExtensionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Тесты продления аукциона (конфигурируемое)")
class AuctionExtensionTest {

    private Clock fixedClock;
    private OffsetDateTime fixedTime;
    private Auction auction;
    private AuctionExtensionProperties extensionProperties;

    private final BigDecimal STARTING_PRICE = new BigDecimal("1000000");
    private final BigDecimal MIN_BID_STEP = new BigDecimal("10000");

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2024-01-01T10:00:00Z");
        fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));
        fixedTime = OffsetDateTime.now(fixedClock);

        // Создаём конфигурацию для тестов
        extensionProperties = new AuctionExtensionProperties();
        extensionProperties.setEnabled(true);
        extensionProperties.setThresholdSeconds(30);
        extensionProperties.setExtensionMinutes(5);
        extensionProperties.setMaxExtensions(0); // безлимитно
    }

    private Auction createTestAuction(OffsetDateTime endTime) {
        Auction a = new Auction(
                1L,
                STARTING_PRICE,
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                MIN_BID_STEP,
                fixedTime.minusHours(1),
                endTime,
                1L,
                fixedClock
        );
        a.setExtensionProperties(extensionProperties);
        a.setId(1L);
        a.setStatus(AuctionStatus.ACTIVE);
        return a;
    }

    @Test
    @DisplayName("Аукцион может продлеваться несколько раз")
    void auction_ShouldExtendMultipleTimes() {
        // given
        OffsetDateTime endTime = fixedTime.plusSeconds(25);
        auction = createTestAuction(endTime);

        assertThat(auction.getExtensionCount()).isEqualTo(0);

        // when - первая ставка
        BigDecimal firstBid = STARTING_PRICE.add(MIN_BID_STEP);
        auction.addBid(firstBid, 2L, fixedTime.plusSeconds(10));
        OffsetDateTime afterFirstExtension = auction.getEndTime();

        // then - первое продление
        assertThat(auction.getEndTime()).isAfter(endTime);
        assertThat(auction.getExtensionCount()).isEqualTo(1);

        // when - вторая ставка (увеличиваем на шаг)
        BigDecimal secondBid = firstBid.add(MIN_BID_STEP);
        auction.addBid(secondBid, 3L, afterFirstExtension.minusSeconds(10));

        // then - второе продление
        assertThat(auction.getEndTime()).isAfter(afterFirstExtension);
        assertThat(auction.getExtensionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Аукцион продлевается только если ставка в последние секунды")
    void auction_ShouldExtendOnlyWhenBidInLastSeconds() {
        // given
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        OffsetDateTime originalEndTime = auction.getEndTime();

        // when - ставка не в последние секунды (через 5 минут)
        BigDecimal bid = STARTING_PRICE.add(MIN_BID_STEP);
        auction.addBid(bid, 2L, fixedTime.plusMinutes(5));

        // then - аукцион не продлился
        assertThat(auction.getEndTime()).isEqualTo(originalEndTime);
        assertThat(auction.getExtensionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Максимальное количество продлений ограничивается конфигурацией")
    void auction_ShouldRespectMaxExtensionsLimit() {
        // given - лимит = 2 продления
        extensionProperties.setMaxExtensions(2);

        OffsetDateTime endTime = fixedTime.plusSeconds(25);
        auction = createTestAuction(endTime);

        BigDecimal currentBid = STARTING_PRICE;
        OffsetDateTime lastBidTime = fixedTime.plusSeconds(10);

        // Делаем 3 ставки
        for (int i = 1; i <= 3; i++) {
            currentBid = currentBid.add(MIN_BID_STEP);
            auction.addBid(currentBid, 100L + i, lastBidTime);
            lastBidTime = auction.getEndTime().minusSeconds(10);
        }

        // then - должно быть только 2 продления (третья ставка не продлила)
        assertThat(auction.getExtensionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Если продление выключено - аукцион не продлевается")
    void auction_ShouldNotExtendWhenDisabled() {
        // given - выключаем продление
        extensionProperties.setEnabled(false);

        OffsetDateTime endTime = fixedTime.plusSeconds(25);
        auction = createTestAuction(endTime);

        OffsetDateTime originalEndTime = auction.getEndTime();

        // when - ставка в последние секунды
        BigDecimal bid = STARTING_PRICE.add(MIN_BID_STEP);
        auction.addBid(bid, 2L, fixedTime.plusSeconds(10));

        // then - аукцион не продлился
        assertThat(auction.getEndTime()).isEqualTo(originalEndTime);
        assertThat(auction.getExtensionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Аукцион может продлеваться бесконечно при maxExtensions = 0")
    void auction_ShouldExtendInfinitely() {
        // given - безлимитное продление
        extensionProperties.setMaxExtensions(0);

        OffsetDateTime endTime = fixedTime.plusSeconds(25);
        auction = createTestAuction(endTime);

        BigDecimal currentBid = STARTING_PRICE;
        OffsetDateTime lastBidTime = fixedTime.plusSeconds(10);

        // Делаем 10 ставок
        for (int i = 1; i <= 10; i++) {
            currentBid = currentBid.add(MIN_BID_STEP);
            auction.addBid(currentBid, 100L + i, lastBidTime);
            lastBidTime = auction.getEndTime().minusSeconds(10);
        }

        // then - все 10 продлений сработали
        assertThat(auction.getExtensionCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("При ставке после истечения времени - исключение")
    void auction_BidAfterExpiration_ThrowsException() {
        // given - время уже истекло
        OffsetDateTime endTime = fixedTime.minusSeconds(10);
        auction = createTestAuction(endTime);

        // then
        assertThrows(IllegalStateException.class, () -> {
            BigDecimal bid = STARTING_PRICE.add(MIN_BID_STEP);
            auction.addBid(bid, 2L, fixedTime);
        });
    }

    @Test
    @DisplayName("При ставке меньше минимального шага - исключение")
    void auction_BidTooLow_ThrowsException() {
        // given
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        // when - ставка слишком мала
        BigDecimal tooLowBid = STARTING_PRICE.add(MIN_BID_STEP).subtract(new BigDecimal("1"));

        // then
        assertThrows(IllegalArgumentException.class, () -> {
            auction.addBid(tooLowBid, 2L, fixedTime.plusMinutes(5));
        });
    }
}