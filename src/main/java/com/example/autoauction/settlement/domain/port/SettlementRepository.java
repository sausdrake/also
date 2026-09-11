package com.example.autoauction.settlement.domain.port;

import com.example.autoauction.settlement.domain.Settlement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Step 2C: persistence port for {@link Settlement}. The domain/application layer depends on this
 * interface only - never on Spring Data / JPA.
 */
public interface SettlementRepository {

    Settlement save(Settlement settlement);

    Optional<Settlement> findById(Long id);

    Optional<Settlement> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);

    /**
     * Atomic {@code PENDING -> PAYOUT_RECORDED} claim, equivalent to
     * {@code UPDATE settlement SET status='PAYOUT_RECORDED', attested_payout_amount=?,
     * recorded_by_admin_id=?, recorded_at=? WHERE id=? AND status='PENDING'}. Returns true only
     * if this call's own UPDATE actually matched a PENDING row - the sole concurrency guard for
     * this transition, mirroring {@code SaleResolutionRepository}'s own claim-first atomic
     * methods. No SELECT-then-mutate-then-save path exists for this transition.
     */
    boolean claimPayoutRecorded(Long settlementId, BigDecimal attestedPayoutAmount,
                                 Long recordedByAdminId, OffsetDateTime recordedAt);
}
