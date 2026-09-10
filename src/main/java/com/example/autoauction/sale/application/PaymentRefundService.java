package com.example.autoauction.sale.application;

import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.PaymentRefundEvidence;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Slice 1 (Sale Completion &amp; Failure API): the sole controlled entry point for recording
 * that the full winning price physically received under a {@link PaymentEvidence} was returned
 * in full to the buyer.
 *
 * Locked design constraints:
 * <ul>
 *   <li>{@link #recordRefund(Long, Long)} accepts only the routing key ({@code auctionId}) and
 *       the authenticated admin identity ({@code refundedByAdminId}). It never accepts
 *       {@code paymentEvidenceId}, {@code buyerId}, or {@code refundedAmount} as independent
 *       input - all three are derived from the {@link PaymentEvidence} actually loaded from the
 *       database, so {@code refundedAmount == PaymentEvidence.amount} holds by construction, not
 *       by a post-hoc check.</li>
 *   <li>Plain {@code @Transactional} (REQUIRED, never REQUIRES_NEW) - it always joins the
 *       caller's physical transaction. The only caller is
 *       {@code SaleFailureResolutionOrchestrator.resolveSaleFailure}; there is no refund
 *       endpoint, no asynchronous path, and no second creation site anywhere.</li>
 * </ul>
 *
 * Deliberately does NOT: change Auction status, touch Vehicle, touch Deposit, calculate
 * commission, or create a SaleResolution - all owned by its single caller / that caller's own
 * collaborators.
 */
@Slf4j
@Service
public class PaymentRefundService {

    private final PaymentRefundEvidenceRepository paymentRefundEvidenceRepository;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final Clock clock;

    public PaymentRefundService(PaymentRefundEvidenceRepository paymentRefundEvidenceRepository,
                                PaymentEvidenceRepository paymentEvidenceRepository,
                                Clock clock) {
        this.paymentRefundEvidenceRepository = paymentRefundEvidenceRepository;
        this.paymentEvidenceRepository = paymentEvidenceRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Records that the winning price received under the {@link PaymentEvidence} for
     * {@code auctionId} was fully returned to the buyer, witnessed by {@code refundedByAdminId}.
     * Every business field of the resulting {@link PaymentRefundEvidence} - paymentEvidenceId,
     * auctionId, buyerId, refundedAmount - is derived from that PaymentEvidence row.
     *
     * @throws IllegalStateException if no PaymentEvidence exists for the auction, or a refund
     *         fact already exists for it (also covers the UNIQUE-constraint race).
     */
    @Transactional
    public PaymentRefundEvidence recordRefund(Long auctionId, Long refundedByAdminId) {
        PaymentEvidence payment = paymentEvidenceRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalStateException(
                        "PaymentRefundEvidence нельзя создать без PaymentEvidence для аукциона " + auctionId));

        if (paymentRefundEvidenceRepository.existsByPaymentEvidenceId(payment.getId())) {
            throw new IllegalStateException(
                    "PaymentRefundEvidence уже существует для PaymentEvidence " + payment.getId());
        }

        PaymentRefundEvidence evidence = new PaymentRefundEvidence(
                payment.getId(), payment.getAuctionId(), payment.getBuyerId(), payment.getAmount(),
                refundedByAdminId, now(), now());
        try {
            PaymentRefundEvidence saved = paymentRefundEvidenceRepository.save(evidence);
            log.info("PaymentRefundEvidence recorded: auction={}, paymentEvidence={}, buyer={}, amount={}, refundedBy={}",
                    payment.getAuctionId(), payment.getId(), payment.getBuyerId(), payment.getAmount(), refundedByAdminId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(
                    "PaymentRefundEvidence уже существует для аукциона " + auctionId, e);
        }
    }
}
