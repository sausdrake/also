package com.example.autoauction.settlement.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.settlement.domain.Settlement;
import com.example.autoauction.settlement.domain.SettlementCorrection;
import com.example.autoauction.settlement.domain.SettlementCorrectionType;
import com.example.autoauction.settlement.domain.SettlementStatus;
import com.example.autoauction.settlement.domain.port.SettlementCorrectionRepository;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 2C Phase 5 corrective pass: real-PostgreSQL, real-thread proof of {@link SettlementService}'s
 * payout and correction concurrency guarantees - the gap Phase 5 explicitly found ("the service
 * checks status first" is not itself sufficient proof; the {@code claimPayoutRecorded} atomic
 * UPDATE and the {@code UNIQUE(idempotency_key)} constraint are the actual authorities). Mirrors
 * {@code IdempotencyConcurrencyIntegrationTest}'s own proven two-thread/CyclicBarrier convention
 * exactly, but against real PostgreSQL (via {@link AbstractPostgresIntegrationTest}) rather than
 * H2 - row-level locking under a real concurrent UPDATE race cannot be faithfully exercised on H2.
 *
 * <p>Each test constructs a bare {@link Settlement} directly via the repository (bypassing
 * {@code createPending}'s own preconditions, which are already proven elsewhere) so the test
 * stays focused on the payout/correction concurrency mechanism itself.
 */
@DisplayName("Step 2C: Settlement payout/correction real-PostgreSQL concurrency proof")
class SettlementConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private SettlementService settlementService;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementCorrectionRepository settlementCorrectionRepository;
    @Autowired private AuctionRepository auctionRepository;

    /**
     * Phase 5 corrective fix: {@code settlement.auction_id} carries a genuine
     * {@code REFERENCES auctions(id)} foreign key (V44) - a synthetic {@code System.nanoTime()}
     * value with no corresponding row fails closed at insert time against real PostgreSQL
     * (correctly - the constraint was doing its job). {@code auctions.vehicle_id}/{@code
     * created_by} carry no FK of their own (confirmed in V2__core_schema.sql), so a minimal,
     * otherwise-unused Auction row is sufficient here - no Vehicle/User/DiagnosticRequest chain
     * is needed merely to satisfy this one FK.
     */
    private Long newRealAuctionId() {
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
        return auctionRepository.save(auction).getId();
    }

    private Settlement newPendingSettlement(Long auctionId, Long sellerId) {
        return settlementRepository.save(new Settlement(auctionId, sellerId,
                new BigDecimal("1000000.00"), new BigDecimal("980000.00"), new BigDecimal("20000.00"),
                OffsetDateTime.now()));
    }

    /**
     * Phase 6 corrective pass (Fix 1): recordCorrection() now requires status PAYOUT_RECORDED -
     * every correction-concurrency scenario below targets a correction, not a payout, so its
     * fixture must first transition through a real recordPayout() the same way Case D already
     * did, rather than leaving the Settlement in PENDING.
     */
    private Settlement newPayoutRecordedSettlement(Long auctionId, Long sellerId, BigDecimal attestedAmount) {
        Settlement pending = newPendingSettlement(auctionId, sellerId);
        pending.recordPayout(attestedAmount, 1L, OffsetDateTime.now());
        return settlementRepository.save(pending);
    }

    private void runConcurrently(Runnable a, Runnable b) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                a.run();
            } catch (Exception ignored) {
                // Runnable wrappers below capture their own exceptions into AtomicReferences;
                // a barrier-timeout here would itself be a test infrastructure failure, not a
                // production outcome, so it is intentionally not swallowed into a captured result.
                throw new RuntimeException(ignored);
            }
        });
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                b.run();
            } catch (Exception ignored) {
                throw new RuntimeException(ignored);
            }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    // ==================== Payout: Case A - concurrent IDENTICAL payout ====================

    @RepeatedTest(5)
    @DisplayName("Payout Case A: два конкурентных запроса с ОДИНАКОВОЙ суммой -> ровно один claim, второй - безопасный replay")
    void payout_ConcurrentIdenticalAmount_ExactlyOneClaimOtherSafeReplay() throws InterruptedException {
        Long auctionId = newRealAuctionId();
        newPendingSettlement(auctionId, 1L);
        BigDecimal amount = new BigDecimal("970000.00");

        AtomicReference<Settlement> resultA = new AtomicReference<>();
        AtomicReference<Settlement> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(settlementService.recordPayout(auctionId, amount, 11L)); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(settlementService.recordPayout(auctionId, amount, 22L)); }
                        catch (Throwable t) { errB.set(t); } });

        // Identical amount is ALWAYS a safe replay regardless of who wins the DB claim race -
        // neither side may throw.
        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();

        Settlement fresh = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
        assertThat(fresh.getAttestedPayoutAmount()).isEqualByComparingTo(amount);
        // Exactly one of the two actors actually won the real DB claim - never both (that would
        // mean two rows/updates), never neither (that would mean no one actually claimed it).
        assertThat(fresh.getRecordedByAdminId()).as("recordedByAdminId must be exactly one of the two actors")
                .isIn(11L, 22L);
        // Both callers' own returned view must agree with the single persisted winner - neither
        // thread may observe a stale/different admin than what is actually durable.
        assertThat(resultA.get().getRecordedByAdminId()).isEqualTo(fresh.getRecordedByAdminId());
        assertThat(resultB.get().getRecordedByAdminId()).isEqualTo(fresh.getRecordedByAdminId());
    }

    // ==================== Payout: Case B - concurrent CONFLICTING payout ====================

    @RepeatedTest(5)
    @DisplayName("Payout Case B: два конкурентных запроса с РАЗНОЙ суммой -> ровно один выигрывает, второй fail-closed")
    void payout_ConcurrentConflictingAmount_ExactlyOneWinsOtherFailsClosed() throws InterruptedException {
        Long auctionId = newRealAuctionId();
        newPendingSettlement(auctionId, 1L);
        BigDecimal amountA = new BigDecimal("970000.00");
        BigDecimal amountB = new BigDecimal("960000.00");

        AtomicReference<Settlement> resultA = new AtomicReference<>();
        AtomicReference<Settlement> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(settlementService.recordPayout(auctionId, amountA, 11L)); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(settlementService.recordPayout(auctionId, amountB, 22L)); }
                        catch (Throwable t) { errB.set(t); } });

        boolean aFailed = errA.get() != null;
        boolean bFailed = errB.get() != null;
        assertThat(aFailed ^ bFailed)
                .as("Exactly one of the two conflicting attempts must fail closed, errA=%s, errB=%s",
                        errA.get(), errB.get())
                .isTrue();
        if (aFailed) {
            assertThat(errA.get()).isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(errB.get()).isInstanceOf(IllegalStateException.class);
        }

        Settlement fresh = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
        // The persisted amount is EXACTLY one of the two attempted values - never a mix, never
        // null/partial.
        boolean matchesA = fresh.getAttestedPayoutAmount().compareTo(amountA) == 0;
        boolean matchesB = fresh.getAttestedPayoutAmount().compareTo(amountB) == 0;
        assertThat(matchesA ^ matchesB)
                .as("persisted amount %s must be exactly one of %s/%s", fresh.getAttestedPayoutAmount(), amountA, amountB)
                .isTrue();
        // No duplicate Settlement for this auction.
        assertThat(settlementRepository.findByAuctionId(auctionId)).isPresent();
    }

    // ==================== Correction: Case A - same key, identical request ====================

    @RepeatedTest(5)
    @DisplayName("Correction Case A: конкурентные запросы с ОДИНАКОВЫМ idempotencyKey и идентичным содержимым -> ровно одна запись")
    void correction_ConcurrentSameKeyIdenticalRequest_ExactlyOneRow() throws InterruptedException {
        Long auctionId = newRealAuctionId();
        newPayoutRecordedSettlement(auctionId, 1L, new BigDecimal("970000.00"));
        String key = "pg-conc-key-" + System.nanoTime();

        AtomicReference<SettlementCorrection> resultA = new AtomicReference<>();
        AtomicReference<SettlementCorrection> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                        "recount", 1L, key)); } catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                        "recount", 1L, key)); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();
        assertThat(resultA.get().getId()).isEqualTo(resultB.get().getId());

        Settlement settlement = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        List<SettlementCorrection> rows = settlementCorrectionRepository
                .findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        assertThat(rows).as("the identical-request replay must never create a second row").hasSize(1);
    }

    // ==================== Correction: Case B - same key, different request ====================

    @RepeatedTest(5)
    @DisplayName("Correction Case B: конкурентные запросы с ОДИНАКОВЫМ idempotencyKey, но разным содержимым -> ровно один устанавливает ключ, второй fail-closed")
    void correction_ConcurrentSameKeyDifferentRequest_ExactlyOneEstablishesKey() throws InterruptedException {
        Long auctionId = newRealAuctionId();
        newPayoutRecordedSettlement(auctionId, 1L, new BigDecimal("970000.00"));
        String key = "pg-conc-key-" + System.nanoTime();

        AtomicReference<SettlementCorrection> resultA = new AtomicReference<>();
        AtomicReference<SettlementCorrection> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                        "reason A", 1L, key)); } catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                        "reason B (different)", 1L, key)); } catch (Throwable t) { errB.set(t); } });

        boolean aFailed = errA.get() != null;
        boolean bFailed = errB.get() != null;
        assertThat(aFailed ^ bFailed)
                .as("Exactly one of the two conflicting same-key attempts must fail closed, errA=%s, errB=%s",
                        errA.get(), errB.get())
                .isTrue();
        if (aFailed) {
            assertThat(errA.get()).isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(errB.get()).isInstanceOf(IllegalStateException.class);
        }

        Settlement settlement = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        List<SettlementCorrection> rows = settlementCorrectionRepository
                .findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        assertThat(rows).as("no ambiguous state - exactly one row exists for this key").hasSize(1);
        assertThat(rows.get(0).getReason()).isIn("reason A", "reason B (different)");
    }

    // ==================== Correction: Case C - different keys, identical content ====================

    @RepeatedTest(3)
    @DisplayName("Correction Case C: РАЗНЫЕ idempotencyKey с идентичным содержимым -> обе корректировки валидны, без случайной дедупликации")
    void correction_DifferentKeysIdenticalContent_BothPersistIndependently() throws InterruptedException {
        Long auctionId = newRealAuctionId();
        newPayoutRecordedSettlement(auctionId, 1L, new BigDecimal("970000.00"));
        String keyA = "pg-conc-keyA-" + System.nanoTime();
        String keyB = "pg-conc-keyB-" + System.nanoTime();

        AtomicReference<SettlementCorrection> resultA = new AtomicReference<>();
        AtomicReference<SettlementCorrection> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.PAYOUT_INVALIDATED, null, null,
                        "identical reason text", 1L, keyA)); } catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.PAYOUT_INVALIDATED, null, null,
                        "identical reason text", 1L, keyB)); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();
        assertThat(resultA.get().getId()).isNotEqualTo(resultB.get().getId());

        Settlement settlement = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        List<SettlementCorrection> rows = settlementCorrectionRepository
                .findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        assertThat(rows)
                .as("identical content under different keys must NOT be deduplicated - both are distinct, valid facts")
                .hasSize(2);
    }

    // ==================== Correction: Case D - concurrent different valid payloads ====================

    @RepeatedTest(3)
    @DisplayName("Correction Case D: конкурентные корректировки с разным содержимым -> обе добавляются независимо, без потери обновления, Settlement не изменён")
    void correction_ConcurrentDifferentValidPayloads_BothAppendIndependently() throws InterruptedException {
        Long auctionId = newRealAuctionId();
        Settlement original = newPendingSettlement(auctionId, 1L);
        original.recordPayout(new BigDecimal("970000.00"), 1L, OffsetDateTime.now());
        settlementRepository.save(original); // not the claim-first path - direct save is fine for test setup

        String keyAmount = "pg-conc-amount-" + System.nanoTime();
        String keySeller = "pg-conc-seller-" + System.nanoTime();

        AtomicReference<SettlementCorrection> resultA = new AtomicReference<>();
        AtomicReference<SettlementCorrection> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                        "amount recount", 1L, keyAmount)); } catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(settlementService.recordCorrection(auctionId,
                        SettlementCorrectionType.SELLER_CORRECTION, null, 999L,
                        "wrong seller", 1L, keySeller)); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();

        Settlement settlement = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        List<SettlementCorrection> rows = settlementCorrectionRepository
                .findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        assertThat(rows).as("both distinct corrections must append independently - no lost update").hasSize(2);

        // The original Settlement row itself is untouched by either correction.
        assertThat(settlement.getSellerId()).isEqualTo(1L);
        assertThat(settlement.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(settlement.getExpectedCommission()).isEqualByComparingTo("20000.00");
        assertThat(settlement.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");

        // Effective state deterministically derived from the persisted correction history.
        var effective = com.example.autoauction.settlement.domain.SettlementEffectiveState.compute(settlement, rows);
        assertThat(effective.effectiveSellerId()).isEqualTo(999L);
        assertThat(effective.effectivePayoutAmount()).isEqualByComparingTo("960000.00");
        assertThat(effective.validPayoutExists()).isTrue();
    }
}
