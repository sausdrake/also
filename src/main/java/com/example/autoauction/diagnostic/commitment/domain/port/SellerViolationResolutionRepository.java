package com.example.autoauction.diagnostic.commitment.domain.port;

import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolutionItem;

import java.util.List;
import java.util.Optional;

/**
 * X3-E: persistence port for the seller-violation resolution audit trail. Both the header and
 * its items are inserted together, exactly once, in the same transaction as the commitment's
 * own claim-first FINALIZED transition - never independently, never updated afterward
 * (immutable by construction, no update method exists here).
 */
public interface SellerViolationResolutionRepository {

    /**
     * Persists {@code resolution} and every one of {@code items} together. {@code items} must
     * already carry the correct (not-yet-assigned) sellerViolationResolutionId reference
     * pattern - implementations are responsible for wiring the header's generated id into each
     * item before insert.
     */
    SellerViolationResolution saveWithItems(SellerViolationResolution resolution, List<SellerViolationResolutionItem> items);

    Optional<SellerViolationResolution> findBySellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId);

    List<SellerViolationResolutionItem> findItemsByResolutionId(Long sellerViolationResolutionId);
}
