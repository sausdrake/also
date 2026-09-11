package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.settlement.domain.Settlement;
import com.example.autoauction.settlement.domain.SettlementCorrection;
import com.example.autoauction.settlement.domain.SettlementCorrectionType;
import com.example.autoauction.settlement.domain.SettlementStatus;
import com.example.autoauction.settlement.domain.port.SettlementCorrectionRepository;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 2C: proves the V44 schema (settlement + settlement_correction + the CHECK constraints)
 * starts against real PostgreSQL (Flyway V1..V44), both aggregates round-trip through their
 * mappers, and the money-identity constraints (UNIQUE(auction_id), UNIQUE(idempotency_key), the
 * conservation CHECK) are actually present in the database - mirrors
 * {@code PaymentPersistenceSmokePgIntegrationTest}'s own established pattern and scope exactly
 * (foundation smoke test only; the full claim-first-update/concurrency proof is a later step, the
 * same discipline Step 1's own payment smoke test followed).
 */
@DisplayName("Step 2C: Settlement/SettlementCorrection persistence smoke (PostgreSQL / Flyway V1..V44)")
class SettlementPersistenceSmokePgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementCorrectionRepository settlementCorrectionRepository;
    @Autowired private SpringDataSettlementJpaRepository springDataSettlementJpaRepository;
    @Autowired private AuctionRepository auctionRepository;

    /**
     * Phase 5 corrective fix: {@code settlement.auction_id} carries a genuine
     * {@code REFERENCES auctions(id)} foreign key (V44) - a synthetic {@code System.nanoTime()}
     * value with no corresponding row fails closed at insert time against real PostgreSQL
     * (correctly - the constraint was doing its job). {@code auctions.vehicle_id}/{@code
     * created_by} carry no FK of their own (confirmed in V2__core_schema.sql), so a minimal,
     * otherwise-unused Auction row is sufficient here.
     */
    private Long newRealAuctionId() {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
        return auctionRepository.save(auction).getId();
    }

    private Settlement newSettlement(Long auctionId, Long sellerId) {
        return new Settlement(auctionId, sellerId, new BigDecimal("1000000.00"),
                new BigDecimal("980000.00"), new BigDecimal("20000.00"), OffsetDateTime.now());
    }

    @Test
    @DisplayName("Settlement round-trips: PENDING -> PAYOUT_RECORDED via claimPayoutRecorded, economics intact")
    void settlementRoundTrip() {
        Long auctionId = newRealAuctionId();
        Long sellerId = 777L;

        Settlement saved = settlementRepository.save(newSettlement(auctionId, sellerId));
        assertThat(saved.getId()).isNotNull();

        Settlement reloaded = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(reloaded.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(reloaded.getAttestedPayoutAmount()).isNull();

        OffsetDateTime recordedAt = OffsetDateTime.now();
        boolean claimed = settlementRepository.claimPayoutRecorded(reloaded.getId(),
                new BigDecimal("970000.00"), 42L, recordedAt);
        assertThat(claimed).isTrue();

        Settlement done = settlementRepository.findById(reloaded.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
        assertThat(done.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
        assertThat(done.getRecordedByAdminId()).isEqualTo(42L);

        // A second claim attempt must not match (no longer PENDING) - the atomic WHERE clause,
        // not an application-level check, is what makes this safe.
        boolean secondClaim = settlementRepository.claimPayoutRecorded(reloaded.getId(),
                new BigDecimal("960000.00"), 99L, OffsetDateTime.now());
        assertThat(secondClaim).isFalse();
        assertThat(settlementRepository.findById(reloaded.getId()).orElseThrow().getAttestedPayoutAmount())
                .as("the losing claim must never have mutated the row")
                .isEqualByComparingTo("970000.00");
    }

    @Test
    @DisplayName("uk_settlement_auction_id is enforced by the database")
    void auctionIdUnique() {
        Long auctionId = newRealAuctionId();
        settlementRepository.save(newSettlement(auctionId, 1L));
        assertThatThrownBy(() -> settlementRepository.save(newSettlement(auctionId, 2L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("settlement_conservation_check rejects a row violating sellerAmount+commission=buyerPaid at the database")
    void conservationCheckConstraint() {
        JpaSettlementEntity bad = new JpaSettlementEntity(null, newRealAuctionId(), 1L,
                new BigDecimal("1000000.00"), new BigDecimal("980000.00"), new BigDecimal("19999.00"),
                SettlementStatus.PENDING, null, null, null, OffsetDateTime.now());
        assertThatThrownBy(() -> springDataSettlementJpaRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("SettlementCorrection round-trips for all three types; settlement_id index supports ordered read")
    void settlementCorrectionRoundTrip() {
        Long auctionId = newRealAuctionId();
        Settlement settlement = settlementRepository.save(newSettlement(auctionId, 1L));

        OffsetDateTime t1 = OffsetDateTime.now();
        SettlementCorrection amount = settlementCorrectionRepository.save(
                new SettlementCorrection(settlement.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                        new BigDecimal("960000.00"), null, "recount", 1L, t1, "pg-key-" + System.nanoTime()));
        OffsetDateTime t2 = t1.plusSeconds(1);
        SettlementCorrection seller = settlementCorrectionRepository.save(
                new SettlementCorrection(settlement.getId(), SettlementCorrectionType.SELLER_CORRECTION,
                        null, 2L, "wrong person", 1L, t2, "pg-key-" + System.nanoTime()));
        OffsetDateTime t3 = t2.plusSeconds(1);
        SettlementCorrection invalidated = settlementCorrectionRepository.save(
                new SettlementCorrection(settlement.getId(), SettlementCorrectionType.PAYOUT_INVALIDATED,
                        null, null, "entirely erroneous", 1L, t3, "pg-key-" + System.nanoTime()));

        var ordered = settlementCorrectionRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(0).getId()).isEqualTo(amount.getId());
        assertThat(ordered.get(1).getId()).isEqualTo(seller.getId());
        assertThat(ordered.get(2).getId()).isEqualTo(invalidated.getId());
    }

    @Test
    @DisplayName("Phase 5 corrective: full correction lifecycle (payout + 3 corrections) leaves the original Settlement row byte-for-byte unchanged in its economic fields")
    void fullCorrectionLifecycle_OriginalSettlementRowRemainsUnchanged() {
        Long auctionId = newRealAuctionId();
        Settlement settlement = settlementRepository.save(newSettlement(auctionId, 1L));
        OffsetDateTime recordedAt = OffsetDateTime.now();

        boolean claimed = settlementRepository.claimPayoutRecorded(settlement.getId(),
                new BigDecimal("970000.00"), 42L, recordedAt);
        assertThat(claimed).isTrue();

        String k1 = "lifecycle-k1-" + System.nanoTime();
        String k2 = "lifecycle-k2-" + System.nanoTime();
        String k3 = "lifecycle-k3-" + System.nanoTime();
        settlementCorrectionRepository.save(new SettlementCorrection(settlement.getId(),
                SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                "recount", 1L, OffsetDateTime.now(), k1));
        settlementCorrectionRepository.save(new SettlementCorrection(settlement.getId(),
                SettlementCorrectionType.SELLER_CORRECTION, null, 2L,
                "wrong seller", 1L, OffsetDateTime.now(), k2));
        settlementCorrectionRepository.save(new SettlementCorrection(settlement.getId(),
                SettlementCorrectionType.PAYOUT_INVALIDATED, null, null,
                "entirely erroneous", 1L, OffsetDateTime.now(), k3));

        // Fresh read from the database, NOT the in-memory object - proves persistence, not just
        // in-memory domain immutability.
        Settlement fresh = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(fresh.getSellerId()).isEqualTo(1L);
        assertThat(fresh.getExpectedBuyerPaidAmount()).isEqualByComparingTo("1000000.00");
        assertThat(fresh.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(fresh.getExpectedCommission()).isEqualByComparingTo("20000.00");
        assertThat(fresh.getAttestedPayoutAmount())
                .as("the ORIGINAL attested amount must remain unchanged after AMOUNT_CORRECTION - " +
                        "corrections are additive, never a mutation of this row")
                .isEqualByComparingTo("970000.00");
        assertThat(fresh.getRecordedByAdminId()).isEqualTo(42L);
        assertThat(fresh.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);

        // All three corrections persisted independently, in the correction table only.
        var corrections = settlementCorrectionRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        assertThat(corrections).hasSize(3);
    }

    @Test
    @DisplayName("uk_settlement_correction_idempotency_key is enforced by the database")
    void idempotencyKeyUnique() {
        Long auctionId = newRealAuctionId();
        Settlement settlement = settlementRepository.save(newSettlement(auctionId, 1L));
        String key = "dup-key-" + System.nanoTime();
        settlementCorrectionRepository.save(new SettlementCorrection(settlement.getId(),
                SettlementCorrectionType.PAYOUT_INVALIDATED, null, null, "reason-1", 1L, OffsetDateTime.now(), key));

        assertThatThrownBy(() -> settlementCorrectionRepository.save(new SettlementCorrection(settlement.getId(),
                SettlementCorrectionType.PAYOUT_INVALIDATED, null, null, "reason-2", 1L, OffsetDateTime.now(), key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
