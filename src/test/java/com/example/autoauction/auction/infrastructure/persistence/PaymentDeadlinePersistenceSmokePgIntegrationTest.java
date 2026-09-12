package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.PaymentDeadlineChangeType;
import com.example.autoauction.auction.domain.PaymentDeadlineHistory;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.domain.port.PaymentDeadlineHistoryRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Slice 1B: proves the V46 schema ({@code auctions.payment_deadline} + {@code
 * payment_deadline_history}, including its CHECK constraints) starts against real PostgreSQL
 * (Flyway V1..V46), round-trips through the mapper, and the database-level invariants are
 * actually present - mirrors {@code SettlementPersistenceSmokePgIntegrationTest}'s own
 * established pattern and scope.
 */
@DisplayName("Slice 1B: payment_deadline / payment_deadline_history persistence smoke (PostgreSQL / Flyway V1..V46)")
class PaymentDeadlinePersistenceSmokePgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private AuctionRepository auctionRepository;
    @Autowired private PaymentDeadlineHistoryRepository paymentDeadlineHistoryRepository;
    @Autowired private SpringDataPaymentDeadlineHistoryJpaRepository springDataRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long newWaitingForPaymentAuctionId() {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        return auctionRepository.save(auction).getId();
    }

    @Test
    @DisplayName("Auction.paymentDeadline round-trips through the real column")
    void auctionPaymentDeadline_RoundTrips() {
        Long auctionId = newWaitingForPaymentAuctionId();
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        OffsetDateTime deadline = OffsetDateTime.now().plusDays(2);
        auction.setPaymentDeadline(deadline);
        auctionRepository.save(auction);

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        // PostgreSQL TIMESTAMP WITH TIME ZONE has microsecond precision (Java OffsetDateTime.now()
        // carries nanoseconds) and is returned normalized to the session/JDBC offset, not the
        // offset originally written - compare the underlying instant, within 1ms, matching this
        // codebase's own "instant-based, not wall-clock-based" comparison convention.
        assertThat(reloaded.getPaymentDeadline().toInstant())
                .isCloseTo(deadline.toInstant(), within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("INITIAL + ADMIN_EXTENSION round-trip; ordered read by (changedAt, id)")
    void historyRoundTrip_OrderedRead() {
        Long auctionId = newWaitingForPaymentAuctionId();
        OffsetDateTime t1 = OffsetDateTime.now();
        OffsetDateTime deadline1 = t1.plusDays(1);
        PaymentDeadlineHistory initial = paymentDeadlineHistoryRepository.save(
                PaymentDeadlineHistory.initial(auctionId, deadline1, t1));

        OffsetDateTime t2 = t1.plusSeconds(5);
        OffsetDateTime deadline2 = deadline1.plusDays(1);
        PaymentDeadlineHistory extension = paymentDeadlineHistoryRepository.save(
                PaymentDeadlineHistory.adminExtension(auctionId, deadline1, deadline2, "покупатель попросил отсрочку", 42L, t2));

        var ordered = paymentDeadlineHistoryRepository.findByAuctionIdOrderByChangedAtAscIdAsc(auctionId);
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getId()).isEqualTo(initial.getId());
        assertThat(ordered.get(0).getChangeType()).isEqualTo(PaymentDeadlineChangeType.INITIAL);
        assertThat(ordered.get(0).getChangedByAdminId()).isNull();
        assertThat(ordered.get(0).getPreviousDeadline()).isNull();
        assertThat(ordered.get(1).getId()).isEqualTo(extension.getId());
        assertThat(ordered.get(1).getChangeType()).isEqualTo(PaymentDeadlineChangeType.ADMIN_EXTENSION);
        assertThat(ordered.get(1).getChangedByAdminId()).isEqualTo(42L);
        assertThat(ordered.get(1).getPreviousDeadline().toInstant())
                .isCloseTo(deadline1.toInstant(), within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("payment_deadline_history.auction_id FK rejects a non-existent auction at the database")
    void auctionIdForeignKey_Enforced() {
        Long fakeAuctionId = System.nanoTime();
        assertThatThrownBy(() -> paymentDeadlineHistoryRepository.save(
                PaymentDeadlineHistory.initial(fakeAuctionId, OffsetDateTime.now().plusDays(1), OffsetDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("payment_deadline_history_change_type_check rejects an unrecognized change_type at the database (raw SQL, bypassing JPA's enum type-safety)")
    void changeTypeCheckConstraint_Enforced() {
        Long auctionId = newWaitingForPaymentAuctionId();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payment_deadline_history " +
                        "(auction_id, change_type, previous_deadline, new_deadline, reason, changed_by_admin_id, changed_at) " +
                        "VALUES (?, 'BOGUS_TYPE', NULL, ?, ?, NULL, ?)",
                auctionId, OffsetDateTime.now().plusDays(1), "reason", OffsetDateTime.now()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("payment_deadline_history_actor_check rejects INITIAL with a non-null admin actor at the database")
    void actorCheckConstraint_RejectsInitialWithAdminActor() {
        Long auctionId = newWaitingForPaymentAuctionId();
        JpaPaymentDeadlineHistoryEntity bad = rawEntityBypassingDomainValidation(
                auctionId, null, OffsetDateTime.now().plusDays(1), "reason", 99L, OffsetDateTime.now());
        assertThatThrownBy(() -> springDataRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("payment_deadline_history_actor_check rejects ADMIN_EXTENSION with a null previousDeadline at the database")
    void actorCheckConstraint_RejectsAdminExtensionWithoutPreviousDeadline() {
        Long auctionId = newWaitingForPaymentAuctionId();
        JpaPaymentDeadlineHistoryEntity bad = new JpaPaymentDeadlineHistoryEntity(null, auctionId,
                PaymentDeadlineChangeType.ADMIN_EXTENSION, null, OffsetDateTime.now().plusDays(1),
                "reason", 42L, OffsetDateTime.now());
        assertThatThrownBy(() -> springDataRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Builds a raw INITIAL-shaped entity directly, bypassing {@code PaymentDeadlineHistory}'s own domain validation. */
    private JpaPaymentDeadlineHistoryEntity rawEntityBypassingDomainValidation(
            Long auctionId, OffsetDateTime previousDeadline, OffsetDateTime newDeadline, String reason,
            Long changedByAdminId, OffsetDateTime changedAt) {
        return new JpaPaymentDeadlineHistoryEntity(null, auctionId, PaymentDeadlineChangeType.INITIAL,
                previousDeadline, newDeadline, reason, changedByAdminId, changedAt);
    }
}
