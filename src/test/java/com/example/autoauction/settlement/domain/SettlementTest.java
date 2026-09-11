package com.example.autoauction.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 2C: Settlement aggregate — conservation invariant & payout lifecycle")
class SettlementTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-10T10:00:00Z");
    private static final Long AUCTION_ID = 1L;
    private static final Long SELLER_ID = 7L;

    private Settlement newSettlement() {
        // buyerPaid=1,000,000; sellerAmount=980,000; commission=20,000 - exact conservation.
        return new Settlement(AUCTION_ID, SELLER_ID, new BigDecimal("1000000.00"),
                new BigDecimal("980000.00"), new BigDecimal("20000.00"), NOW);
    }

    @Nested
    @DisplayName("creation / conservation invariant")
    class Creation {

        @Test
        void validCreation_startsInPending() {
            Settlement s = newSettlement();
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.PENDING);
            assertThat(s.getAuctionId()).isEqualTo(AUCTION_ID);
            assertThat(s.getSellerId()).isEqualTo(SELLER_ID);
            assertThat(s.getExpectedBuyerPaidAmount()).isEqualByComparingTo("1000000.00");
            assertThat(s.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
            assertThat(s.getExpectedCommission()).isEqualByComparingTo("20000.00");
            assertThat(s.getAttestedPayoutAmount()).isNull();
            assertThat(s.getRecordedByAdminId()).isNull();
            assertThat(s.getRecordedAt()).isNull();
        }

        @Test
        void conservationViolated_rejected() {
            // 980000 + 20000.01 != 1000000
            assertThatThrownBy(() -> new Settlement(AUCTION_ID, SELLER_ID, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.01"), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("инвариант сохранения");
        }

        @Test
        void zeroOrNegativeBuyerPaidOrSellerAmount_rejected() {
            assertThatThrownBy(() -> new Settlement(AUCTION_ID, SELLER_ID, BigDecimal.ZERO,
                    new BigDecimal("980000"), new BigDecimal("20000"), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Settlement(AUCTION_ID, SELLER_ID, new BigDecimal("1000000"),
                    BigDecimal.ZERO, new BigDecimal("1000000"), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void negativeCommission_rejected() {
            assertThatThrownBy(() -> new Settlement(AUCTION_ID, SELLER_ID, new BigDecimal("1000000"),
                    new BigDecimal("1000001"), new BigDecimal("-1"), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullRequiredFields_rejected() {
            assertThatThrownBy(() -> new Settlement(null, SELLER_ID, new BigDecimal("1000000"),
                    new BigDecimal("980000"), new BigDecimal("20000"), NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Settlement(AUCTION_ID, null, new BigDecimal("1000000"),
                    new BigDecimal("980000"), new BigDecimal("20000"), NOW))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void zeroCommission_accepted_sellerGetsEverything() {
            // Legal: a zero-commission sale is still a valid conservation split.
            Settlement s = new Settlement(AUCTION_ID, SELLER_ID, new BigDecimal("500000.00"),
                    new BigDecimal("500000.00"), BigDecimal.ZERO, NOW);
            assertThat(s.getExpectedCommission()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("recordPayout lifecycle")
    class RecordPayout {

        @Test
        void pendingToPayoutRecorded_setsAllFieldsAtomically() {
            Settlement s = newSettlement();
            OffsetDateTime recordedAt = NOW.plusDays(1);
            s.recordPayout(new BigDecimal("970000.00"), 99L, recordedAt);

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
            assertThat(s.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
            assertThat(s.getRecordedByAdminId()).isEqualTo(99L);
            assertThat(s.getRecordedAt()).isEqualTo(recordedAt);
        }

        @Test
        void expectedEconomicsRemainUnchangedAfterPayout() {
            Settlement s = newSettlement();
            s.recordPayout(new BigDecimal("960000.00"), 99L, NOW);

            // The 20,000 discrepancy (980000 expected vs 960000 attested) is preserved, not
            // reconciled away - expected fields are never touched by recordPayout.
            assertThat(s.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
            assertThat(s.getExpectedCommission()).isEqualByComparingTo("20000.00");
            assertThat(s.getExpectedBuyerPaidAmount()).isEqualByComparingTo("1000000.00");
        }

        @Test
        void secondRecordPayoutAttempt_rejected_noStatusRollback() {
            Settlement s = newSettlement();
            s.recordPayout(new BigDecimal("980000.00"), 99L, NOW);

            assertThatThrownBy(() -> s.recordPayout(new BigDecimal("980000.00"), 100L, NOW.plusDays(1)))
                    .isInstanceOf(IllegalStateException.class);
            // Original recording is untouched by the rejected second attempt.
            assertThat(s.getRecordedByAdminId()).isEqualTo(99L);
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
        }

        @Test
        void nonPositiveAttestedAmount_rejected() {
            Settlement s = newSettlement();
            assertThatThrownBy(() -> s.recordPayout(BigDecimal.ZERO, 99L, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.PENDING);
        }

        @Test
        void nullAdminOrTimestamp_rejected() {
            Settlement s = newSettlement();
            assertThatThrownBy(() -> s.recordPayout(new BigDecimal("980000"), null, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> s.recordPayout(new BigDecimal("980000"), 99L, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Phase 6 corrective pass (Fix 2): attestedAmount must be normalized to the same
         * canonical scale-2/HALF_UP convention createPending already applies to its own computed
         * amounts - otherwise the in-memory object/HTTP response could show a higher-scale value
         * than what PostgreSQL's NUMERIC(19,2) actually persists.
         */
        @Test
        void attestedAmount_normalizedToScale2HalfUp() {
            Settlement s = newSettlement();
            s.recordPayout(new BigDecimal("970000.005"), 99L, NOW);
            assertThat(s.getAttestedPayoutAmount()).isEqualByComparingTo("970000.01");
        }

        @Test
        void attestedAmount_halfUpBoundary_roundsDownWhenBelowHalf() {
            Settlement s = newSettlement();
            s.recordPayout(new BigDecimal("970000.004"), 99L, NOW);
            assertThat(s.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
        }
    }

    @Nested
    @DisplayName("reconstruction")
    class Reconstruction {

        @Test
        void reconstruction_revalidatesConservation_rejectsCorruptedRow() {
            assertThatThrownBy(() -> new Settlement(1L, AUCTION_ID, SELLER_ID, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("19999.00"), SettlementStatus.PENDING,
                    null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("инвариант сохранения");
        }

        @Test
        void reconstruction_restoresPayoutRecordedRowExactly() {
            Settlement s = new Settlement(1L, AUCTION_ID, SELLER_ID, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PAYOUT_RECORDED,
                    new BigDecimal("970000.00"), 99L, NOW.plusDays(1), NOW);
            assertThat(s.getId()).isEqualTo(1L);
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
            assertThat(s.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
            assertThat(s.getRecordedByAdminId()).isEqualTo(99L);
        }
    }
}
