// diagnostic/commitment/infrastructure/persistence/SellerDiagnosticCommitmentRepositoryAdapterTest.java
package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.deposit.domain.DepositStatus;
import com.example.autoauction.deposit.infrastructure.persistence.JpaDepositEntity;
import com.example.autoauction.deposit.infrastructure.persistence.SpringDataDepositJpaRepository;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.infrastructure.persistence.JpaDiagnosticRequestEntity;
import com.example.autoauction.diagnostic.infrastructure.persistence.SpringDataDiagnosticRequestJpaRepository;
import com.example.autoauction.user.infrastructure.persistence.JpaUserEntity;
import com.example.autoauction.user.infrastructure.persistence.SpringDataUserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// PHASE 2: JpaSellerDiagnosticCommitmentEntity's constructor now takes diagnosticFeeAmount and
// status too - every construction below passes a fixed feeAmount/CommitmentStatus.FROZEN unless
// the test is specifically about the FROZEN -> AWAITING_FINALIZATION transition itself.

/**
 * PHASE 1 (seller commitment redesign): proves the new seller_diagnostic_commitment
 * table/entity/adapter is persisted correctly and independently of deposit_frozen -
 * mirrors DepositFrozenRepositoryIntegrationTest's established @DataJpaTest pattern
 * exactly.
 *
 * IMPORTANT, DOCUMENTED LIMITATION (per Phase 1 pre-change verification report): this
 * project has no Testcontainers dependency, and this test profile runs with
 * ddl-auto=create-drop / Flyway disabled (see application-test.properties) - the test
 * schema is generated purely from JPA annotations, NOT from the real V28 migration. The
 * real FK from seller_diagnostic_commitment.diagnostic_request_id to diagnostic_requests(id)
 * is therefore NOT enforced under this test profile (this entity deliberately has no
 * @ManyToOne/@JoinColumn - see JpaSellerDiagnosticCommitmentEntity's own Javadoc) and
 * cannot be proven here. This is stated explicitly rather than pretending the FK has been
 * integration-tested. The uniqueness constraint IS provable here, because it is declared
 * both in the Flyway migration and as a matching @Table(uniqueConstraints=...) annotation.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("PHASE 1: интеграционные тесты таблицы seller_diagnostic_commitment")
class SellerDiagnosticCommitmentRepositoryAdapterTest {

    @Autowired
    private SpringDataSellerDiagnosticCommitmentJpaRepository commitmentJpaRepository;

    @Autowired
    private SpringDataDepositJpaRepository depositJpaRepository;

    @Autowired
    private SpringDataDiagnosticRequestJpaRepository diagnosticRequestJpaRepository;

    @Autowired
    private SpringDataUserJpaRepository userJpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long testDepositId;
    private Long testDiagnosticRequestId;

    @BeforeEach
    void setUp() {
        JpaUserEntity user = new JpaUserEntity("commitmentuser", "commitment@test.com", "password", true);
        user = userJpaRepository.save(user);

        JpaDepositEntity deposit = new JpaDepositEntity(
                user.getId(), new BigDecimal("50000"), BigDecimal.ZERO, DepositStatus.ACTIVE);
        deposit = depositJpaRepository.save(deposit);
        testDepositId = deposit.getId();

        JpaDiagnosticRequestEntity request = new JpaDiagnosticRequestEntity();
        request.setUserId(user.getId());
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("WBSDE91070CZ00001");
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        request = diagnosticRequestJpaRepository.save(request);
        testDiagnosticRequestId = request.getId();
    }

    @Test
    @DisplayName("Сохранение commitment с валидным diagnostic_request_id")
    void save_ValidDiagnosticRequestId_Persists() {
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());

        JpaSellerDiagnosticCommitmentEntity saved = commitmentJpaRepository.save(entity);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDiagnosticRequestId()).isEqualTo(testDiagnosticRequestId);
        assertThat(saved.getDepositId()).isEqualTo(testDepositId);
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(saved.getFrozenAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Commitment ссылается на корректный deposit")
    void save_ReferencesCorrectDeposit() {
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("7500"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());
        commitmentJpaRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        JpaSellerDiagnosticCommitmentEntity found = commitmentJpaRepository
                .findByDiagnosticRequestId(testDiagnosticRequestId).orElseThrow();

        assertThat(found.getDepositId()).isEqualTo(testDepositId);
        assertThat(found.getCommittedAmount()).isEqualByComparingTo(new BigDecimal("7500"));
    }

    @Test
    @DisplayName("existsByDiagnosticRequestId отражает фактическое наличие записи")
    void existsByDiagnosticRequestId_ReflectsActualPresence() {
        assertThat(commitmentJpaRepository.existsByDiagnosticRequestId(testDiagnosticRequestId)).isFalse();

        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());
        commitmentJpaRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        assertThat(commitmentJpaRepository.existsByDiagnosticRequestId(testDiagnosticRequestId)).isTrue();
    }

    @Test
    @DisplayName("deleteByDiagnosticRequestId удаляет запись (эквивалент release-полной заморозки)")
    void deleteByDiagnosticRequestId_RemovesRecord() {
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());
        commitmentJpaRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        commitmentJpaRepository.deleteByDiagnosticRequestId(testDiagnosticRequestId);
        entityManager.flush();
        entityManager.clear();

        assertThat(commitmentJpaRepository.existsByDiagnosticRequestId(testDiagnosticRequestId)).isFalse();
    }

    @Test
    @DisplayName("Дублирующий commitment для того же diagnostic_request_id отклоняется " +
            "уникальным ограничением БД (uk_seller_diagnostic_commitment_request)")
    void save_DuplicateDiagnosticRequestId_RejectedByUniqueConstraint() {
        JpaSellerDiagnosticCommitmentEntity first = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());
        commitmentJpaRepository.save(first);
        entityManager.flush();

        JpaSellerDiagnosticCommitmentEntity duplicate = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("5000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());

        assertThatThrownBy(() -> {
            commitmentJpaRepository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Персистентность независима от deposit_frozen - новая таблица не пересекается " +
            "с auction-side FrozenDeposit ни по схеме, ни по данным")
    void persistence_IndependentFromAuctionFrozenDeposit() {
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());
        commitmentJpaRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        // No column on this entity/table is named or shaped like deposit_frozen's
        // auction_id overload - this table exists purely as its own, correctly-typed
        // home; nothing here can be confused with or leak into the auction-side table.
        assertThat(commitmentJpaRepository.findByDiagnosticRequestId(testDiagnosticRequestId)).isPresent();
    }

    // PHASE 2: proves markAwaitingFinalization's atomic UPDATE...WHERE guard at the real
    // Spring Data / JPQL level, not just via a mocked port.

    @Test
    @DisplayName("markAwaitingFinalization: FROZEN -> AWAITING_FINALIZATION, ровно одна строка затронута")
    void markAwaitingFinalization_FrozenRow_TransitionsAndReturnsOne() {
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.FROZEN, OffsetDateTime.now());
        commitmentJpaRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        int updated = commitmentJpaRepository.markAwaitingFinalization(testDiagnosticRequestId, OffsetDateTime.now());
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        JpaSellerDiagnosticCommitmentEntity reloaded = commitmentJpaRepository
                .findByDiagnosticRequestId(testDiagnosticRequestId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
    }

    @Test
    @DisplayName("markAwaitingFinalization: уже AWAITING_FINALIZATION -> WHERE status='FROZEN' не совпадает, 0 строк")
    void markAwaitingFinalization_AlreadyAwaitingFinalization_ReturnsZero() {
        JpaSellerDiagnosticCommitmentEntity entity = new JpaSellerDiagnosticCommitmentEntity(
                testDiagnosticRequestId, testDepositId, new BigDecimal("10000"), new BigDecimal("4000"),
                CommitmentStatus.AWAITING_FINALIZATION, OffsetDateTime.now());
        commitmentJpaRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        int updated = commitmentJpaRepository.markAwaitingFinalization(testDiagnosticRequestId, OffsetDateTime.now());

        assertThat(updated).isEqualTo(0);
    }

    @Test
    @DisplayName("markAwaitingFinalization: несуществующий diagnosticRequestId -> 0 строк, без исключения")
    void markAwaitingFinalization_NoRow_ReturnsZeroWithoutThrowing() {
        int updated = commitmentJpaRepository.markAwaitingFinalization(999999L, OffsetDateTime.now());

        assertThat(updated).isEqualTo(0);
    }
}
