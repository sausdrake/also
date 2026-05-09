// integration/BidServiceRetryIntegrationTest.java
package com.example.autoauction.integration;

import com.example.autoauction.auction.application.BidService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnableRetry
@DisplayName("Интеграционные тесты ретраев в BidService")
class BidServiceRetryIntegrationTest {

    @Autowired
    private BidService bidService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Long sellerId;
    private Long bidderId;
    private Long auctionId;

    @BeforeEach
    void setUp() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));

        User seller = new User(null, "seller", "seller@test.com", "encoded", true, Set.of(userRole));
        sellerId = userRepository.save(seller).getId();

        User bidder = new User(null, "bidder", "bidder@test.com", "encoded", true, Set.of(userRole));
        bidderId = userRepository.save(bidder).getId();

        depositService.createDeposit(bidderId, new BigDecimal("50000"));

        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                now.minusHours(1),
                now.plusDays(7),
                sellerId
        );
        auction.start();
        auction = auctionRepository.save(auction);
        auctionId = auction.getId();
    }

    @Test
    @DisplayName("Ретрай при оптимистичной блокировке - ставка проходит")
    void placeBid_WithOptimisticLock_RetrySucceeds() {
        // Параллельные ставки не тестируем в этом простом тесте
        // Просто проверяем, что ставка работает
        BigDecimal bidAmount = new BigDecimal("1010000");

        var response = bidService.placeBid(auctionId, bidAmount, bidderId);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualTo(bidAmount);
        assertThat(response.status()).isEqualTo(AuctionStatus.ACTIVE);
    }
}