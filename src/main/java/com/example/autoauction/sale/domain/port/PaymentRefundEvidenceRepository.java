package com.example.autoauction.sale.domain.port;

import com.example.autoauction.sale.domain.PaymentRefundEvidence;

import java.util.Optional;

/**
 * Slice 1: same shape and reasoning as {@code PaymentEvidenceRepository}/
 * {@code HandoverEvidenceRepository} - exposes only what PaymentRefundEvidence's own
 * immutability supports: a single insert plus read-backs. No update, no delete. A
 * PaymentRefundEvidence row, once inserted, is never mutated or removed by any code path.
 */
public interface PaymentRefundEvidenceRepository {

    PaymentRefundEvidence save(PaymentRefundEvidence paymentRefundEvidence);

    Optional<PaymentRefundEvidence> findByAuctionId(Long auctionId);

    Optional<PaymentRefundEvidence> findByPaymentEvidenceId(Long paymentEvidenceId);

    boolean existsByAuctionId(Long auctionId);

    boolean existsByPaymentEvidenceId(Long paymentEvidenceId);
}
