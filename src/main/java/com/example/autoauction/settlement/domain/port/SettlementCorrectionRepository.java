package com.example.autoauction.settlement.domain.port;

import com.example.autoauction.settlement.domain.SettlementCorrection;

import java.util.List;
import java.util.Optional;

/**
 * Step 2C: persistence port for {@link SettlementCorrection}.
 */
public interface SettlementCorrectionRepository {

    /**
     * Persists a new correction. {@code idempotencyKey} is enforced uniquely at the database
     * level (V44); implementations must run this insert in its own independent transaction so
     * that a concurrent same-key conflict fails and rolls back without poisoning the caller's
     * transaction/session - see {@code SettlementCorrectionRepositoryAdapter.save}'s Javadoc for
     * the full reasoning.
     */
    SettlementCorrection save(SettlementCorrection correction);

    Optional<SettlementCorrection> findByIdempotencyKey(String idempotencyKey);

    /**
     * Ordered by {@code correctedAt} then {@code id} ascending - required for deterministic
     * effective-state folding (see {@code SettlementEffectiveState.compute}, which re-sorts
     * defensively but relies on this method for the primary ordering).
     */
    List<SettlementCorrection> findBySettlementIdOrderByCorrectedAtAscIdAsc(Long settlementId);
}
