// integration/AuctionFlowIntegrationTest.java
package com.example.autoauction.integration;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Интеграционные тесты аукциона")
class AuctionFlowIntegrationTest {

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidService bidService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuctionParticipantRepository participantRepository;

    private Long sellerId;
    private Long bidder1Id;
    private Long bidder2Id;
    private Long auctionId;
    private Auction auction;
    private Role userRole;

    @BeforeEach
    void setUp() {
        userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));

        User seller = new User(null, "seller", "seller@test.com", "encoded", true, Set.of(userRole));
        sellerId = userRepository.save(seller).getId();

        User bidder1 = new User(null, "bidder1", "bidder1@test.com", "encoded", true, Set.of(userRole));
        User bidder2 = new User(null, "bidder2", "bidder2@test.com", "encoded", true, Set.of(userRole));
        bidder1Id = userRepository.save(bidder1).getId();
        bidder2Id = userRepository.save(bidder2).getId();

        depositService.createDeposit(bidder1Id, new BigDecimal("50000"));
        depositService.createDeposit(bidder2Id, new BigDecimal("50000"));

        OffsetDateTime startTime = OffsetDateTime.now().minusHours(1);
        OffsetDateTime endTime = startTime.plusDays(7);

        auction = new Auction(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                startTime,
                endTime,
                sellerId
        );
        auction.start();
        auction = auctionRepository.save(auction);
        auctionId = auction.getId();
    }

    @Test
    @DisplayName("Полный цикл: первая ставка -> вторая ставка -> проверка победителя")
    void fullAuctionFlow_Success() {
        // Первая ставка
        BigDecimal firstBid = new BigDecimal("1010000");
        BidResponse firstResponse = bidService.placeBid(auctionId, firstBid, bidder1Id);

        assertThat(firstResponse).isNotNull();
        assertThat(firstResponse.newCurrentPrice()).isEqualTo(firstBid);
        assertThat(firstResponse.totalBids()).isEqualTo(1);
        assertThat(firstResponse.status()).isEqualTo(AuctionStatus.ACTIVE);

        // Проверяем участника
        boolean isParticipant = participantRepository.isParticipant(auctionId, bidder1Id);
        assertThat(isParticipant).isTrue();

        // Вторая ставка
        BigDecimal secondBid = new BigDecimal("1020000");
        BidResponse secondResponse = bidService.placeBid(auctionId, secondBid, bidder2Id);

        assertThat(secondResponse).isNotNull();
        assertThat(secondResponse.newCurrentPrice()).isEqualTo(secondBid);
        assertThat(secondResponse.totalBids()).isEqualTo(2);
        assertThat(secondResponse.status()).isEqualTo(AuctionStatus.ACTIVE);

        // Проверяем второго участника
        isParticipant = participantRepository.isParticipant(auctionId, bidder2Id);
        assertThat(isParticipant).isTrue();

        // Проверяем список участников
        java.util.List<Long> participants = participantRepository.getParticipants(auctionId);
        assertThat(participants).hasSize(2);
        assertThat(participants).containsExactlyInAnyOrder(bidder1Id, bidder2Id);

        // Проверяем депозиты
        var deposit1 = depositRepository.findByUserId(bidder1Id).orElseThrow();
        var deposit2 = depositRepository.findByUserId(bidder2Id).orElseThrow();
        assertThat(deposit1.getBlockedAmount()).isPositive();
        assertThat(deposit2.getBlockedAmount()).isPositive();
    }

    @Test
    @DisplayName("Создание ставки на свой аукцион - ошибка")
    void placeBid_OnOwnAuction_ThrowsException() {
        BigDecimal bid = new BigDecimal("1010000");
        assertThatThrownBy(() -> bidService.placeBid(auctionId, bid, sellerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("собственный");
    }

    @Test
    @DisplayName("Ставка ниже минимального шага - ошибка")
    void placeBid_BelowMinStep_ThrowsException() {
        BigDecimal tooLowBid = new BigDecimal("1005000");
        assertThatThrownBy(() -> bidService.placeBid(auctionId, tooLowBid, bidder1Id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("слишком мала");
    }

    @Test
    @DisplayName("Buy Now до начала аукциона - успешная покупка")
    void buyNow_BeforeAuctionStart_Success() {
        // Создаём аукцион, который ещё не стартовал
        OffsetDateTime futureStart = OffsetDateTime.now().plusDays(1);
        Auction notStartedAuction = new Auction(
                100L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                futureStart,
                futureStart.plusDays(7),
                sellerId
        );
        notStartedAuction = auctionRepository.save(notStartedAuction);

        // Покупатель с достаточным депозитом
        User buyer = new User(null, "buyer", "buyer@test.com", "encoded", true, Set.of(userRole));
        Long buyerId = userRepository.save(buyer).getId();
        depositService.createDeposit(buyerId, new BigDecimal("1000000"));

        BidResponse response = bidService.buyNow(notStartedAuction.getId(), buyerId);

        assertThat(response.buyNowUsed()).isTrue();
        assertThat(response.status()).isEqualTo(AuctionStatus.COMPLETED);
        assertThat(response.newCurrentPrice()).isEqualTo(new BigDecimal("1500000"));
    }

    @Test
    @DisplayName("Buy Now во время активного аукциона - ошибка")
    void buyNow_DuringActiveAuction_ThrowsException() {
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThatThrownBy(() -> bidService.buyNow(auctionId, bidder1Id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("доступен только для аукционов в статусе CREATED");
    }

    @Test
    @DisplayName("Buy Now на аукцион без Buy Now цены - ошибка")
    void buyNow_NoBuyNowPrice_ThrowsException() {
        // Создаём аукцион без Buy Now цены
        OffsetDateTime futureStart = OffsetDateTime.now().plusDays(1);
        Auction noBuyNowAuction = new Auction(
                101L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                null,
                new BigDecimal("10000"),
                futureStart,
                futureStart.plusDays(7),
                sellerId
        );
        noBuyNowAuction = auctionRepository.save(noBuyNowAuction);

        User buyer = new User(null, "buyer2", "buyer2@test.com", "encoded", true, Set.of(userRole));
        Long buyerId = userRepository.save(buyer).getId();
        depositService.createDeposit(buyerId, new BigDecimal("1000000"));

        Auction finalNoBuyNowAuction = noBuyNowAuction;
        assertThatThrownBy(() -> bidService.buyNow(finalNoBuyNowAuction.getId(), buyerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Buy Now не установлен");
    }

    @Test
    @DisplayName("Buy Now на свой аукцион - ошибка")
    void buyNow_OnOwnAuction_ThrowsException() {
        OffsetDateTime futureStart = OffsetDateTime.now().plusDays(1);
        Auction ownAuction = new Auction(
                102L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                futureStart,
                futureStart.plusDays(7),
                sellerId
        );
        ownAuction = auctionRepository.save(ownAuction);

        Auction finalOwnAuction = ownAuction;
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            bidService.buyNow(finalOwnAuction.getId(), sellerId);
        });

        assertEquals("Нельзя купить собственный автомобиль", exception.getMessage());
    }

    @Test
    @DisplayName("Buy Now с недостаточным депозитом - ошибка")
    void buyNow_InsufficientDeposit_ThrowsException() {
        OffsetDateTime futureStart = OffsetDateTime.now().plusDays(1);
        Auction notStartedAuction = new Auction(
                103L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                futureStart,
                futureStart.plusDays(7),
                sellerId
        );
        notStartedAuction = auctionRepository.save(notStartedAuction);

        // Покупатель с нулевым депозитом
        User poorBuyer = new User(null, "poor", "poor@test.com", "encoded", true, Set.of(userRole));
        Long poorBuyerId = userRepository.save(poorBuyer).getId();
        depositService.createDeposit(poorBuyerId, BigDecimal.ZERO);

        Auction finalNotStartedAuction = notStartedAuction;
        assertThatThrownBy(() -> bidService.buyNow(finalNotStartedAuction.getId(), poorBuyerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недостаточно средств");
    }

    @Test
    @DisplayName("Один пользователь не может заморозить депозит дважды на один аукцион")
    void sameUser_CannotFreezeTwice() {
        BigDecimal firstBid = new BigDecimal("1010000");
        bidService.placeBid(auctionId, firstBid, bidder1Id);

        java.util.List<Long> participants = participantRepository.getParticipants(auctionId);
        assertThat(participants).hasSize(1);
        assertThat(participants).containsExactly(bidder1Id);
    }

    @Test
    @DisplayName("Недостаточно средств на депозите - ошибка")
    void placeBid_InsufficientDeposit_ThrowsException() {
        User poorUser = new User(null, "poor", "poor@test.com", "encoded", true, Set.of(userRole));
        Long poorUserId = userRepository.save(poorUser).getId();
        depositService.createDeposit(poorUserId, BigDecimal.ZERO);

        BigDecimal bid = new BigDecimal("1010000");
        assertThatThrownBy(() -> bidService.placeBid(auctionId, bid, poorUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недостаточно средств");
    }
}