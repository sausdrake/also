package com.example.autoauction.sale.domain.port;

import com.example.autoauction.sale.domain.PaymentEvidence;

import java.util.Optional;

/**
 * X1: deliberately exposes only what PaymentEvidence's own immutability actually supports - no
 * update, no delete. A PaymentEvidence row, once inserted, is never mutated or removed by any
 * code path.
 */
public interface PaymentEvidenceRepository {

    PaymentEvidence save(PaymentEvidence paymentEvidence);

    Optional<PaymentEvidence> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);
}
