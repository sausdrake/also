package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.PaymentDeadlineHistory;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.domain.port.PaymentDeadlineHistoryRepository;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Slice 1 (Sale Completion &amp; Failure API): the application boundary that turns a
 * {@code WAITING_FOR_PAYMENT} auction into its terminal outcome - {@code SOLD} via
 * {@link #completeSale} or {@code SALE_FAILED} via {@link #failSale} - under a single physical
 * transaction that holds the Auction pessimistic write lock from the first read to commit.
 *
 * A peer of {@link AuctionFinalizer}/{@link SaleFailureResolutionOrchestrator}, not a facade
 * over the evidence services. Its own responsibilities are narrow and real:
 * <ul>
 *   <li>own the {@code @Transactional} boundary and acquire {@code findByIdWithLock} so that
 *       lock acquisition and the state-changing finalizer call share one physical transaction
 *       (a pessimistic lock is held only for the life of its enclosing transaction);</li>
 *   <li>derive {@link FaultParty} server-side from the admin-selected {@code outcome} - the
 *       client never supplies a fault party;</li>
 *   <li>propagate the authenticated admin identity as {@code resolvedBy};</li>
 *   <li>normalise the optional audit {@code reason} (whitespace-only -&gt; null);</li>
 *   <li>delegate the actual terminal transition + all financial consequences to the existing
 *       finalizer / orchestrator, unchanged.</li>
 * </ul>
 *
 * It deliberately owns NONE of: PaymentEvidence / HandoverEvidence / PaymentRefundEvidence
 * creation, commission, seller payout, deposit policy, seller-violation calculation, or any
 * HTTP concern.
 */
@Slf4j
@Service
public class SaleAdministrationService {

    private final AuctionRepository auctionRepository;
    private final AuctionFinalizer auctionFinalizer;
    private final SaleFailureResolutionOrchestrator saleFailureResolutionOrchestrator;
    private final PaymentDeadlineHistoryRepository paymentDeadlineHistoryRepository;
    private final Clock clock;

    public SaleAdministrationService(AuctionRepository auctionRepository,
                                     AuctionFinalizer auctionFinalizer,
                                     SaleFailureResolutionOrchestrator saleFailureResolutionOrchestrator,
                                     PaymentDeadlineHistoryRepository paymentDeadlineHistoryRepository,
                                     Clock clock) {
        this.auctionRepository = auctionRepository;
        this.auctionFinalizer = auctionFinalizer;
        this.saleFailureResolutionOrchestrator = saleFailureResolutionOrchestrator;
        this.paymentDeadlineHistoryRepository = paymentDeadlineHistoryRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Completes the sale of a {@code WAITING_FOR_PAYMENT} auction: {@code SOLD} + Vehicle
     * {@code SOLD} + buyer-deposit release + Outcome-A seller-commitment resolution, atomically.
     * Every precondition (payment/handover evidence existence, buyer/vehicle/amount matching,
     * status) is re-validated by {@link AuctionFinalizer#completeSale} /
     * {@code SaleResolutionService.createSuccess} under this transaction's lock - not duplicated
     * here.
     */
    @Transactional
    public SaleResolution completeSale(Long auctionId, Long adminId) {
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));
        log.info("Admin {} completing sale of auction {}", adminId, auctionId);
        return auctionFinalizer.completeSale(auction, adminId);
    }

    /**
     * Resolves a {@code WAITING_FOR_PAYMENT} auction as a failed sale. {@code faultParty} is
     * derived from {@code outcome} here and never accepted from the client. If a
     * {@code PaymentEvidence} exists, {@link SaleFailureResolutionOrchestrator} creates the
     * mandatory {@code PaymentRefundEvidence} in this same transaction before finalizing.
     *
     * No DTO-level "C requires violationDefinitionIds" rule is applied - the orchestrator's own
     * conditional check (only when the auction has a linked seller commitment) remains
     * authoritative, so a historical auction with no commitment can still resolve C with an
     * empty list.
     */
    @Transactional
    public SaleResolution failSale(Long auctionId, SaleResolutionResult outcome,
                                   List<Long> violationDefinitionIds, String reason, Long adminId) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome обязателен");
        }
        if (outcome == SaleResolutionResult.SUCCESS) {
            throw new IllegalArgumentException(
                    "failSale() не принимает SUCCESS - успешная продажа завершается через completeSale()");
        }
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        FaultParty faultParty = deriveFaultParty(outcome);
        String normalisedReason = (reason == null || reason.isBlank()) ? null : reason.strip();

        log.info("Admin {} failing sale of auction {} as {} (faultParty={})",
                adminId, auctionId, outcome, faultParty);
        return saleFailureResolutionOrchestrator.resolveSaleFailure(
                auction, outcome, faultParty, normalisedReason, adminId, violationDefinitionIds);
    }

    /**
     * Slice 1B: admin-driven payment-deadline override/extension - unlimited, admin-only, only
     * while {@code WAITING_FOR_PAYMENT} (locked rules). Loads the Auction via
     * {@code findByIdWithLock}, mirroring {@link #completeSale}/{@link #failSale}'s own
     * established pattern, so a concurrent extension or a concurrent {@code completeSale}/
     * {@code failSale} on the same auction serializes through the same lock - no new locking
     * mechanism is introduced. {@code reason} is mandatory (locked rule); the previous deadline
     * and the authenticated admin actor are recorded in the same transaction as the Auction
     * update, as an append-only {@link PaymentDeadlineHistory} row.
     */
    @Transactional
    public Auction extendPaymentDeadline(Long auctionId, OffsetDateTime newDeadline, String reason, Long adminId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason обязателен для изменения платёжного дедлайна");
        }
        Objects.requireNonNull(newDeadline, "newDeadline не может быть null");
        Objects.requireNonNull(adminId, "adminId не может быть null");

        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        OffsetDateTime previousDeadline = auction.getPaymentDeadline();
        auction.extendPaymentDeadline(newDeadline);
        auctionRepository.save(auction);

        paymentDeadlineHistoryRepository.save(PaymentDeadlineHistory.adminExtension(
                auctionId, previousDeadline, newDeadline, reason.strip(), adminId, now()));

        log.info("Admin {} extended payment deadline for auction {} to {}", adminId, auctionId, newDeadline);
        return auction;
    }

    /** Slice 1B: ordered history of every deadline change (INITIAL + every ADMIN_EXTENSION). */
    @Transactional(readOnly = true)
    public List<PaymentDeadlineHistory> getPaymentDeadlineHistory(Long auctionId) {
        return paymentDeadlineHistoryRepository.findByAuctionIdOrderByChangedAtAscIdAsc(auctionId);
    }

    /**
     * The one server-side {@code outcome -> faultParty} mapping this slice adds. Agrees exactly
     * with {@code SaleResolution}'s own {@code validateFaultPartyConsistency} (SUCCESS/B -&gt;
     * null, BUYER_FAULT -&gt; BUYER, PLATFORM_FAULT -&gt; PLATFORM, C -&gt; SELLER); the domain
     * constructor is still the authoritative enforcement point.
     */
    private FaultParty deriveFaultParty(SaleResolutionResult outcome) {
        return switch (outcome) {
            case SUCCESS, B -> null;
            case BUYER_FAULT -> FaultParty.BUYER;
            case PLATFORM_FAULT -> FaultParty.PLATFORM;
            case C -> FaultParty.SELLER;
        };
    }
}
