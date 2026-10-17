// auction/domain/AuctionExtensionTest.java
package com.example.autoauction.auction.domain;

import com.example.autoauction.config.AuctionExtensionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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

    private AuctionExtensionProperties props(int thresholdSeconds, int extensionMinutes, int maxExtensions) {
        AuctionExtensionProperties p = new AuctionExtensionProperties();
        p.setEnabled(true);
        p.setThresholdSeconds(thresholdSeconds);
        p.setExtensionMinutes(extensionMinutes);
        p.setMaxExtensions(maxExtensions);
        return p;
    }

    private Auction auctionWithoutInjectedProperties(OffsetDateTime endTime) {
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
        a.setId(1L);
        a.setStatus(AuctionStatus.ACTIVE);
        return a;
    }

    private BigDecimal firstBidAmount() {
        return STARTING_PRICE.add(MIN_BID_STEP);
    }

    private void assertExtendedOnce(Auction a, OffsetDateTime oldEnd, long minutes) {
        assertThat(a.getEndTime()).isEqualTo(oldEnd.plusMinutes(minutes));
        assertThat(a.getExtensionCount()).isEqualTo(1);
    }

    private void assertNotExtended(Auction a, OffsetDateTime oldEnd) {
        assertThat(a.getEndTime()).isEqualTo(oldEnd);
        assertThat(a.getExtensionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Граница: ставка ровно за thresholdSeconds до конца продлевает (<= включительно)")
    void extends_whenExactlyThresholdSecondsLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minusSeconds(60));

        assertExtendedOnce(auction, endTime, 5);
    }

    @Test
    @DisplayName("Граница: ставка за thresholdSeconds + 1 до конца не продлевает")
    void doesNotExtend_whenThresholdPlusOneSecondLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minusSeconds(61));

        assertNotExtended(auction, endTime);
    }

    @Test
    @DisplayName("Ставка ровно в endTime принимается, но с 0 секунд до конца не продлевает")
    void doesNotExtend_whenBidAtEndTime_zeroSecondsLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime);

        assertNotExtended(auction, endTime);
        assertThat(auction.getTotalBids()).isEqualTo(1);
        assertThat(auction.getCurrentPrice()).isEqualByComparingTo(firstBidAmount());
    }

    @Test
    @DisplayName("Усечение до целых секунд: threshold + 0.999с -> threshold секунд -> продлевает")
    void extends_whenThresholdPlus999MillisLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minus(Duration.ofMillis(60_999)));

        assertExtendedOnce(auction, endTime, 5);
    }

    @Test
    @DisplayName("Усечение до целых секунд: threshold + 1.000с не продлевает")
    void doesNotExtend_whenThresholdPlus1000MillisLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minus(Duration.ofMillis(61_000)));

        assertNotExtended(auction, endTime);
    }

    @Test
    @DisplayName("Усечение до целых секунд: осталось 0.5с -> 0 секунд -> не продлевает")
    void doesNotExtend_whenHalfSecondLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minus(Duration.ofMillis(500)));

        assertNotExtended(auction, endTime);
        assertThat(auction.getTotalBids()).isEqualTo(1);
    }

    @Test
    @DisplayName("Осталась ровно 1 секунда -> продлевает")
    void extends_whenOneSecondLeft() {
        extensionProperties = props(60, 5, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minusSeconds(1));

        assertExtendedOnce(auction, endTime, 5);
    }

    @Test
    @DisplayName("Каждое продление сдвигает endTime ровно на extensionMinutes от старого endTime")
    void extension_movesEndByExactlyExtensionMinutes_fromOldEndTime() {
        extensionProperties = props(60, 7, 0);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        OffsetDateTime firstBidTime = endTime.minusSeconds(20);
        auction.addBid(firstBidAmount(), 2L, firstBidTime);

        OffsetDateTime afterFirst = auction.getEndTime();
        assertThat(afterFirst).isEqualTo(endTime.plusMinutes(7));
        assertThat(afterFirst).isNotEqualTo(firstBidTime.plusMinutes(7));
        assertThat(auction.getExtensionCount()).isEqualTo(1);

        auction.addBid(firstBidAmount().add(MIN_BID_STEP), 3L, afterFirst.minusSeconds(60));

        assertThat(auction.getEndTime()).isEqualTo(afterFirst.plusMinutes(7));
        assertThat(auction.getExtensionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("maxExtensions = 1: вторая поздняя ставка принимается, но не продлевает")
    void maxExtensions1_secondLateBidAccepted_butNotExtended() {
        extensionProperties = props(60, 5, 1);
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = createTestAuction(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minusSeconds(10));
        OffsetDateTime afterFirst = auction.getEndTime();
        assertThat(afterFirst).isEqualTo(endTime.plusMinutes(5));
        assertThat(auction.getExtensionCount()).isEqualTo(1);

        BigDecimal secondBid = firstBidAmount().add(MIN_BID_STEP);
        auction.addBid(secondBid, 3L, afterFirst.minusSeconds(10));

        assertThat(auction.getEndTime()).isEqualTo(afterFirst);
        assertThat(auction.getExtensionCount()).isEqualTo(1);
        assertThat(auction.getCurrentPrice()).isEqualByComparingTo(secondBid);
        assertThat(auction.getTotalBids()).isEqualTo(2);
    }

    @Test
    @DisplayName("Без внедрённых свойств: fallback 30с / 5 мин (не 300 как в production) - ровно 30с продлевает")
    void fallback_extendsAt30SecondsByFiveMinutes() {
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = auctionWithoutInjectedProperties(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minusSeconds(30));

        assertExtendedOnce(auction, endTime, 5);
    }

    @Test
    @DisplayName("Без внедрённых свойств: за 31с до конца не продлевает")
    void fallback_doesNotExtendAt31Seconds() {
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = auctionWithoutInjectedProperties(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minusSeconds(31));

        assertNotExtended(auction, endTime);
    }

    @Test
    @DisplayName("Без внедрённых свойств: за 30.999с до конца продлевает (усечение)")
    void fallback_extendsAt30Point999Seconds() {
        OffsetDateTime endTime = fixedTime.plusMinutes(10);
        auction = auctionWithoutInjectedProperties(endTime);

        auction.addBid(firstBidAmount(), 2L, endTime.minus(Duration.ofMillis(30_999)));

        assertExtendedOnce(auction, endTime, 5);
    }
}