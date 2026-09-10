package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * Slice 1: real-DB proof of the locked concurrency and atomicity invariants for
 * {@link SaleAdministrationService} - the Auction pessimistic write lock serializes every
 * terminal operation, exactly one wins, the loser produces no durable side effect, and a
 * failure anywhere in the fail path rolls back the PaymentRefundEvidence together with the
 * SaleResolution and the Auction transition.
 *
 * {@code @SpyBean SaleResolutionService} wraps the real bean (precedented by
 * SaleCommitmentResolutionRollbackIntegrationTest); it is only stubbed in the one rollback
 * test and reset between methods.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Slice 1: SaleAdministrationService concurrency + refund atomicity (integration)")
class SaleAdministrationConcurrencyAndRefundIntegrationTest {

    @Autowired private SaleAdministrationService saleAdministrationService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DepositService depositService;
    @Autowired private PaymentEvidenceService paymentEvidenceService;
    @Autowired private HandoverEvidenceService handoverEvidenceService;
    @Autowired private PaymentEvidenceRepository paymentEvidenceRepository;
    @Autowired private PaymentRefundEvidenceRepository paymentRefundEvidenceRepository;
    @Autowired private SaleResolutionRepository saleResolutionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @SpyBean private SaleResolutionService saleResolutionService;

    private final BigDecimal winningBid = new BigDecimal("1020000.00");
    private final Long adminId = 1L;

    private TransactionTemplate txTemplate() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return t;
    }

    private record Fixture(Long auctionId, Long vehicleId, Long winnerId) {}

    private Fixture waitingForPayment(boolean withEvidence) {
        Long winnerId = 810_000L + (System.nanoTime() % 100_000_000L);
        Fixture f = txTemplate().execute(s -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                    "SC" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            vehicle.markInAuction();
            Long vehicleId = vehicleRepository.save(vehicle).getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            Long auctionId = auctionRepository.save(auction).getId();
            return new Fixture(auctionId, vehicleId, winnerId);
        });

        txTemplate().execute(s -> {
            depositService.createDeposit(f.winnerId(), new BigDecimal("1000000"));
            return null;
        });
        txTemplate().execute(s -> {
            depositService.freezeForAuction(f.winnerId(), f.auctionId(), new BigDecimal("50000"));
            return null;
        });
        txTemplate().execute(s -> {
            depositService.markAsWinner(f.winnerId(), f.auctionId());
            return null;
        });

        if (withEvidence) {
            txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));
            txTemplate().execute(s -> handoverEvidenceService.recordHandover(f.auctionId(), adminId));
        }
        return f;
    }

    // ==================== complete vs fail ====================

    @Test
    @DisplayName("Concurrent /complete vs /fail: ровно один терминальный исход; ровно один SaleResolution; согласованность")
    void concurrentCompleteVsFail_ExactlyOneWins() throws Exception {
        Fixture f = waitingForPayment(true);

        AtomicReference<Throwable> errComplete = new AtomicReference<>();
        AtomicReference<Throwable> errFail = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); saleAdministrationService.completeSale(f.auctionId(), adminId); }
            catch (Throwable t) { errComplete.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null, null, adminId); }
            catch (Throwable t) { errFail.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        boolean completeFailed = errComplete.get() != null;
        boolean failFailed = errFail.get() != null;
        assertThat(completeFailed ^ failFailed)
                .as("exactly one of complete/fail must fail. errComplete=%s errFail=%s", errComplete.get(), errFail.get())
                .isTrue();

        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(finalAuction.getStatus()).isIn(AuctionStatus.SOLD, AuctionStatus.SALE_FAILED);
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId())).isPresent();

        if (finalAuction.getStatus() == AuctionStatus.SOLD) {
            assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId()))
                    .as("SOLD must never carry refund evidence").isFalse();
        } else {
            assertThat(paymentRefundEvidenceRepository.findByAuctionId(f.auctionId()).orElseThrow()
                    .getRefundedAmount()).isEqualByComparingTo(winningBid);
        }
    }

    // ==================== fail vs fail ====================

    @Test
    @DisplayName("Concurrent /fail(BUYER_FAULT) vs /fail(C): ровно один исход; ровно одна refund-запись; ровно один SaleResolution")
    void concurrentFailVsFail_ExactlyOneWins() throws Exception {
        Fixture f = waitingForPayment(false);
        txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.BUYER_FAULT, null, null, adminId); }
            catch (Throwable t) { errA.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.C, java.util.List.of(), null, adminId); }
            catch (Throwable t) { errB.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat((errA.get() != null) ^ (errB.get() != null))
                .as("exactly one fail must win. errA=%s errB=%s", errA.get(), errB.get()).isTrue();

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId())).isPresent();
        var refund = paymentRefundEvidenceRepository.findByAuctionId(f.auctionId());
        assertThat(refund).as("exactly one refund evidence").isPresent();
        assertThat(refund.orElseThrow().getRefundedAmount()).isEqualByComparingTo(winningBid);
    }

    // ==================== payment vs fail ====================

    @Test
    @DisplayName("Concurrent /payment vs /fail: никогда нет durable SALE_FAILED + PaymentEvidence без PaymentRefundEvidence")
    void concurrentPaymentVsFail_NoRefundInvariantViolation() throws Exception {
        Fixture f = waitingForPayment(false);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> errPay = new AtomicReference<>();
        AtomicReference<Throwable> errFail = new AtomicReference<>();

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId)); }
            catch (Throwable t) { errPay.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null, null, adminId); }
            catch (Throwable t) { errFail.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        boolean paymentExists = paymentEvidenceRepository.existsByAuctionId(f.auctionId());
        boolean refundExists = paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId());

        if (finalAuction.getStatus() == AuctionStatus.SALE_FAILED && paymentExists) {
            assertThat(refundExists)
                    .as("durable SALE_FAILED with PaymentEvidence MUST have matching PaymentRefundEvidence")
                    .isTrue();
        }
        // The only other legal end states: SALE_FAILED with no payment (payment lost the race),
        // or still WAITING_FOR_PAYMENT is impossible here (fail always succeeds unless it lost to
        // nothing) - fail has no competing terminal op, so it always commits SALE_FAILED.
        assertThat(finalAuction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
    }

    // ==================== refund atomicity / rollback ====================

    @Test
    @DisplayName("Откат fail-транзакции после вставки refund evidence: ничего не сохраняется (refund, SaleResolution, статус)")
    void failRollbackAfterRefundInsert_EverythingRollsBack() {
        Fixture f = waitingForPayment(false);
        txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));

        // createFailure runs AFTER paymentRefundService.recordRefund in the same physical
        // transaction - forcing it to throw proves the refund insert is rolled back with it.
        doThrow(new RuntimeException("injected: createFailure failed after refund insert"))
                .when(saleResolutionService).createFailure(anyLong(), any(), any(), any(), anyLong());

        assertThatThrownBy(() ->
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.BUYER_FAULT, null, null, adminId))
                .isInstanceOf(RuntimeException.class);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId()))
                .as("refund evidence must have been rolled back").isFalse();
        assertThat(saleResolutionRepository.existsByAuctionId(f.auctionId())).isFalse();
        assertThat(vehicleRepository.findById(f.vehicleId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.IN_AUCTION);
    }

    // ==================== refund not created when no payment ====================

    @Test
    @DisplayName("Fail без PaymentEvidence: SALE_FAILED, но PaymentRefundEvidence не создаётся")
    void failWithoutPayment_NoRefundEvidence() {
        Fixture f = waitingForPayment(false);

        saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.PLATFORM_FAULT, null, null, adminId);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId()).orElseThrow().getFaultParty())
                .isEqualTo(FaultParty.PLATFORM);
    }
}
