package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * X1 (Sale Evidence + Sale Resolution foundation): the sole controlled entry point for
 * recording that a buyer paid an auction's winning amount. Depends directly on
 * AuctionRepository (the auction module's own port) rather than any auction-side application
 * service - this mirrors an already-established cross-module precedent in this codebase
 * (AdminAuctionService, DashboardController and LotSubscriptionService all depend on
 * AuctionRepository directly), not a new pattern invented for this slice.
 *
 * Deliberately does NOT: mark Auction SOLD, modify Vehicle, release buyer deposit, calculate
 * commission, finalize seller commitment, or create a SaleResolution. All of that belongs to
 * later slices.
 */
@Slf4j
@Service
public class PaymentEvidenceService {

    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final AuctionRepository auctionRepository;
    private final Clock clock;

    public PaymentEvidenceService(PaymentEvidenceRepository paymentEvidenceRepository,
                                   AuctionRepository auctionRepository,
                                   Clock clock) {
        this.paymentEvidenceRepository = paymentEvidenceRepository;
        this.auctionRepository = auctionRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Records that the auction's winning buyer paid {@code amount} for {@code auctionId},
     * received by {@code receivedByAdminId}. Validates every precondition explicitly - see the
     * locked X1 design, plus the Slice 1 correction below - before persisting anything.
     *
     * Slice 1 (Sale Completion &amp; Failure API) corrections:
     * <ul>
     *   <li>The Auction is fetched with a pessimistic write lock ({@code findByIdWithLock}),
     *       inside this same {@code @Transactional} method, so a concurrent {@code /fail}
     *       cannot transition the auction to SALE_FAILED between this method's status check and
     *       its insert. Without the lock, a PaymentEvidence could be committed for an auction
     *       that is already durably SALE_FAILED - with no matching PaymentRefundEvidence,
     *       violating the locked refund invariant.</li>
     *   <li>{@code buyerId} is no longer a caller-supplied parameter - it is derived from
     *       {@code auction.getWinnerId()}. The client never supplies buyer identity.</li>
     * </ul>
     */
    @Transactional
    public PaymentEvidence recordPayment(Long auctionId, BigDecimal amount, Long receivedByAdminId) {
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    "PaymentEvidence можно создать только для аукциона в статусе WAITING_FOR_PAYMENT, текущий: "
                            + auction.getStatus());
        }
        if (auction.getWinnerId() == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " нет победителя");
        }
        Long buyerId = auction.getWinnerId();
        if (auction.getWinningBid() == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " не установлена winningBid");
        }
        if (amount == null || amount.compareTo(auction.getWinningBid()) != 0) {
            throw new IllegalArgumentException(
                    "amount (" + amount + ") не равен winningBid аукциона (" + auction.getWinningBid() + ")");
        }
        if (paymentEvidenceRepository.existsByAuctionId(auctionId)) {
            throw new IllegalStateException("PaymentEvidence уже существует для аукциона " + auctionId);
        }

        PaymentEvidence evidence = new PaymentEvidence(auctionId, buyerId, amount, receivedByAdminId, now(), now());
        try {
            PaymentEvidence saved = paymentEvidenceRepository.save(evidence);
            log.info("PaymentEvidence recorded: auction={}, buyer={}, amount={}, receivedBy={}",
                    auctionId, buyerId, amount, receivedByAdminId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("PaymentEvidence уже существует для аукциона " + auctionId, e);
        }
    }
}
