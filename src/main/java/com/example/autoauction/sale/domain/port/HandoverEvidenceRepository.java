package com.example.autoauction.sale.domain.port;

import com.example.autoauction.sale.domain.HandoverEvidence;

import java.util.Optional;

/**
 * X1: same shape and reasoning as PaymentEvidenceRepository - no update, no delete.
 */
public interface HandoverEvidenceRepository {

    HandoverEvidence save(HandoverEvidence handoverEvidence);

    Optional<HandoverEvidence> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);
}
