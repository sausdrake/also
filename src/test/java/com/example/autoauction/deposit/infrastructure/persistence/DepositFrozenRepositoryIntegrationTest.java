// deposit/infrastructure/persistence/DepositFrozenRepositoryIntegrationTest.java
package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.auction.infrastructure.persistence.JpaAuctionEntity;
import com.example.autoauction.auction.infrastructure.persistence.SpringDataAuctionJpaRepository;
import com.example.autoauction.deposit.domain.DepositStatus;
import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.user.infrastructure.persistence.JpaUserEntity;
import com.example.autoauction.user.infrastructure.persistence.SpringDataUserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Интеграционные тесты таблицы deposit_frozen")
class DepositFrozenRepositoryIntegrationTest {

    @Autowired
    private SpringDataDepositFrozenJpaRepository frozenJpaRepository;

    @Autowired
    private SpringDataDepositJpaRepository depositJpaRepository;

    @Autowired
    private SpringDataAuctionJpaRepository auctionJpaRepository;

    @Autowired
    private SpringDataUserJpaRepository userJpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long testDepositId;
    private Long testAuctionId;

    @BeforeEach
    void setUp() {
        JpaUserEntity user = new JpaUserEntity("testuser", "test@test.com", "password", true);
        user = userJpaRepository.save(user);

        JpaDepositEntity deposit = new JpaDepositEntity(
                user.getId(),
                new BigDecimal("50000"),
                BigDecimal.ZERO,
                DepositStatus.ACTIVE
        );
        deposit = depositJpaRepository.save(deposit);
        testDepositId = deposit.getId();

        JpaAuctionEntity auction = createAuctionEntity(1L, user.getId());
        auction = auctionJpaRepository.save(auction);
        testAuctionId = auction.getId();
    }

    private JpaAuctionEntity createAuctionEntity(Long vehicleId, Long createdBy) {
        try {
            var constructor = JpaAuctionEntity.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            JpaAuctionEntity auction = constructor.newInstance();

            auction.setVehicleId(vehicleId);
            auction.setStartingPrice(new BigDecimal("1000000"));
            auction.setCurrentPrice(new BigDecimal("1000000"));
            auction.setMinBidStep(new BigDecimal("10000"));
            auction.setStartTime(OffsetDateTime.now().plusDays(1));
            auction.setEndTime(OffsetDateTime.now().plusDays(7));
            auction.setStatus(com.example.autoauction.auction.domain.AuctionStatus.CREATED);
            auction.setCreatedBy(createdBy);
            auction.setCreatedAt(OffsetDateTime.now());
            auction.setUpdatedAt(OffsetDateTime.now());

            return auction;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JpaAuctionEntity via reflection", e);
        }
    }

    @Test
    @DisplayName("Сохранение заморозки в БД")
    void saveFrozenDeposit_ShouldPersist() {
        JpaDepositFrozenEntity frozen = new JpaDepositFrozenEntity(
                testDepositId, testAuctionId, new BigDecimal("10000"), FreezeReason.FIRST_BID
        );

        JpaDepositFrozenEntity saved = frozenJpaRepository.save(frozen);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDepositId()).isEqualTo(testDepositId);
        assertThat(saved.getAuctionId()).isEqualTo(testAuctionId);
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(saved.getFreezeReason()).isEqualTo(FreezeReason.FIRST_BID);
        assertThat(saved.isWinner()).isFalse();
        assertThat(saved.getFrozenAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Поиск заморозки по depositId и auctionId")
    void findByDepositIdAndAuctionId_ShouldReturnFrozen() {
        JpaDepositFrozenEntity frozen = new JpaDepositFrozenEntity(
                testDepositId, testAuctionId, new BigDecimal("10000"), FreezeReason.FIRST_BID
        );
        frozenJpaRepository.save(frozen);
        entityManager.flush();
        entityManager.clear();

        var found = frozenJpaRepository.findByDepositIdAndAuctionId(testDepositId, testAuctionId);

        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("Удаление заморозки по auctionId")
    void deleteByAuctionId_ShouldRemoveAll() {
        JpaDepositFrozenEntity frozen1 = new JpaDepositFrozenEntity(
                testDepositId, testAuctionId, new BigDecimal("10000"), FreezeReason.FIRST_BID
        );

        JpaUserEntity user2 = new JpaUserEntity("testuser2", "test2@test.com", "password", true);
        user2 = userJpaRepository.save(user2);

        JpaDepositEntity deposit2 = new JpaDepositEntity(
                user2.getId(),
                new BigDecimal("50000"),
                BigDecimal.ZERO,
                DepositStatus.ACTIVE
        );
        deposit2 = depositJpaRepository.save(deposit2);

        JpaDepositFrozenEntity frozen2 = new JpaDepositFrozenEntity(
                deposit2.getId(), testAuctionId, new BigDecimal("15000"), FreezeReason.FIRST_BID
        );

        frozenJpaRepository.save(frozen1);
        frozenJpaRepository.save(frozen2);
        entityManager.flush();
        entityManager.clear();

        assertThat(frozenJpaRepository.findByAuctionId(testAuctionId)).hasSize(2);

        frozenJpaRepository.deleteByAuctionId(testAuctionId);
        entityManager.flush();
        entityManager.clear();

        assertThat(frozenJpaRepository.findByAuctionId(testAuctionId)).isEmpty();
    }

    @Test
    @DisplayName("Отметка победителя")
    void markAsWinner_ShouldUpdateFlag() {
        // given - создаём запись
        JpaDepositFrozenEntity frozen = new JpaDepositFrozenEntity(
                testDepositId, testAuctionId, new BigDecimal("10000"), FreezeReason.FIRST_BID
        );
        frozen = frozenJpaRepository.save(frozen);

        // Принудительно сбрасываем persistence context
        entityManager.flush();
        entityManager.clear();

        // Проверяем, что изначально isWinner = false (свежие данные из БД)
        JpaDepositFrozenEntity beforeUpdate = frozenJpaRepository.findById(frozen.getId()).orElseThrow();
        assertThat(beforeUpdate.isWinner()).as("Initial isWinner should be false").isFalse();

        // when - выполняем обновление
        frozenJpaRepository.markAsWinner(testDepositId, testAuctionId);

        // ПРИНУДИТЕЛЬНО сбрасываем persistence context после обновления
        // Это гарантирует, что мы читаем данные из БД, а не из кэша
        entityManager.flush();
        entityManager.clear();

        // then - загружаем свежие данные из БД
        JpaDepositFrozenEntity afterUpdate = frozenJpaRepository.findById(frozen.getId()).orElseThrow();
        assertThat(afterUpdate.isWinner()).as("isWinner should be true after markAsWinner").isTrue();
    }
}