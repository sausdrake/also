package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.user.infrastructure.persistence.JpaUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuctionParticipantRepositoryAdapter.class})
@DisplayName("Тесты репозитория участников аукционов")
class JpaAuctionParticipantRepositoryAdapterTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AuctionParticipantRepository participantRepository;

    private Long testAuctionId;
    private Long testUserId1;
    private Long testUserId2;

    @BeforeEach
    void setUp() {
        // Создаём тестового пользователя
        JpaUserEntity user1 = new JpaUserEntity("user1", "user1@test.com", "pass", true);
        JpaUserEntity user2 = new JpaUserEntity("user2", "user2@test.com", "pass", true);
        entityManager.persist(user1);
        entityManager.persist(user2);
        testUserId1 = user1.getId();
        testUserId2 = user2.getId();

        // Создаём тестовый аукцион
        JpaAuctionEntity auction = new JpaAuctionEntity();
        auction.setVehicleId(1L);
        auction.setStartingPrice(new BigDecimal("1000000"));
        auction.setCurrentPrice(new BigDecimal("1000000"));
        auction.setMinBidStep(new BigDecimal("10000"));
        auction.setStartTime(OffsetDateTime.now().plusDays(1));
        auction.setEndTime(OffsetDateTime.now().plusDays(7));
        auction.setStatus(AuctionStatus.CREATED);
        auction.setCreatedBy(1L);
        auction.setCreatedAt(OffsetDateTime.now());
        auction.setUpdatedAt(OffsetDateTime.now());
        entityManager.persist(auction);
        testAuctionId = auction.getId();

        entityManager.flush();
    }

    @Test
    @DisplayName("Регистрация участника - должна сохраняться в БД")
    void registerParticipant_ShouldSave() {
        // when
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));

        // then
        boolean isParticipant = participantRepository.isParticipant(testAuctionId, testUserId1);
        assertThat(isParticipant).isTrue();
    }

    @Test
    @DisplayName("Повторная регистрация того же участника - не создаёт дубль")
    void registerParticipant_Duplicate_ShouldNotCreateDuplicate() {
        // when
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));

        // then
        List<Long> participants = participantRepository.getParticipants(testAuctionId);
        assertThat(participants).hasSize(1);
        assertThat(participants).containsExactly(testUserId1);
    }

    @Test
    @DisplayName("Получение всех участников аукциона")
    void getParticipants_ShouldReturnAll() {
        // given
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));
        participantRepository.registerParticipant(testAuctionId, testUserId2, new BigDecimal("10000"));

        // when
        List<Long> participants = participantRepository.getParticipants(testAuctionId);

        // then
        assertThat(participants).hasSize(2);
        assertThat(participants).containsExactlyInAnyOrder(testUserId1, testUserId2);
    }

    @Test
    @DisplayName("Проверка существования участника")
    void isParticipant_ShouldReturnCorrectValue() {
        // given
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));

        // then
        assertThat(participantRepository.isParticipant(testAuctionId, testUserId1)).isTrue();
        assertThat(participantRepository.isParticipant(testAuctionId, testUserId2)).isFalse();
        assertThat(participantRepository.isParticipant(999L, testUserId1)).isFalse();
    }

    @Test
    @DisplayName("Отметка победителя")
    void markAsWinner_ShouldExecuteWithoutError() {
        // given
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));

        // when - should not throw exception
        participantRepository.markAsWinner(testAuctionId, testUserId1);

        // then
        assertThat(participantRepository.isParticipant(testAuctionId, testUserId1)).isTrue();
    }

    @Test
    @DisplayName("Очистка всех участников аукциона")
    void clearParticipants_ShouldRemoveAll() {
        // given
        participantRepository.registerParticipant(testAuctionId, testUserId1, new BigDecimal("10000"));
        participantRepository.registerParticipant(testAuctionId, testUserId2, new BigDecimal("10000"));
        assertThat(participantRepository.getParticipants(testAuctionId)).hasSize(2);

        // when
        participantRepository.clearParticipants(testAuctionId);

        // then
        assertThat(participantRepository.getParticipants(testAuctionId)).isEmpty();
    }
}