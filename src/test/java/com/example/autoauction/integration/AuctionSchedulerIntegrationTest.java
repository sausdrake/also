// integration/AuctionSchedulerIntegrationTest.java
package com.example.autoauction.integration;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.infrastructure.scheduler.AuctionCompletionScheduler;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Интеграционные тесты шедулера аукционов")
class AuctionSchedulerIntegrationTest {

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionCompletionScheduler scheduler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long sellerId;
    private Long expiredAuctionId;
    private Long vehicleId;

    @BeforeEach
    @Transactional
    void setUp() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));

        User seller = new User(null, "seller", "seller@test.com", "encoded", true, Set.of(userRole));
        sellerId = userRepository.save(seller).getId();

        OffsetDateTime startTime = OffsetDateTime.now().minusDays(2);
        OffsetDateTime endTime = OffsetDateTime.now().minusHours(1);

        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, "SCHED000000000001", VehicleType.SEDAN, 9L, "diag");
        vehicle.setStatus(VehicleStatus.IN_AUCTION);
        vehicleId = vehicleRepository.save(vehicle).getId();

        Auction expiredAuction = new Auction(
                vehicleId,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                startTime,
                endTime,
                sellerId
        );
        expiredAuction.start();
        expiredAuction = auctionRepository.save(expiredAuction);
        expiredAuctionId = expiredAuction.getId();

        entityManager.flush();
    }

    @Test
    @DisplayName("Шедулер находит и завершает протухшие аукционы")
    void scheduler_CompletesExpiredAuctions() {
        // Проверяем, что аукцион активен
        Auction before = auctionRepository.findById(expiredAuctionId).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(AuctionStatus.ACTIVE);

        // Запускаем шедулер
        scheduler.completeExpiredAuctions();

        entityManager.flush();
        entityManager.clear();

        Auction after = auctionRepository.findById(expiredAuctionId).orElseThrow();

        // Аукцион должен быть завершён (без ставок - EXPIRED; со ставками - ожидает подтверждения продажи)
        assertThat(after.getStatus()).isIn(AuctionStatus.EXPIRED, AuctionStatus.WAITING_FOR_PAYMENT);
        // AUC-NOSALE-01: a lot that expired without bids no longer leaves its vehicle IN_AUCTION
        if (after.getStatus() == AuctionStatus.EXPIRED) {
            assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getStatus())
                    .isEqualTo(VehicleStatus.NEEDS_REDIAGNOSTIC);
        }
    }
}