// sale/infrastructure/persistence/SaleEvidenceAndResolutionIntegrationTest.java
package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * X1 (Sale Evidence + Sale Resolution foundation): real-transaction proof of FK/UNIQUE/CHECK
 * enforcement and claim-first financial-status transitions. Deliberately NOT class-level
 * {@code @Transactional} - several tests need genuinely separate, independently-committed
 * transactions (e.g. proving a second creation attempt conflicts with an already-committed
 * first one), matching the TransactionTemplate-based convention already established in this
 * codebase's other transaction-boundary integration tests
 * (AuctionCompletionSchedulerTransactionIsolationIntegrationTest and this session's own
 * AuctionVehicleStartIntegrationTest).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("X1: PaymentEvidence / HandoverEvidence / SaleResolution — real persistence")
class SaleEvidenceAndResolutionIntegrationTest {

    @Autowired
    private PaymentEvidenceService paymentEvidenceService;

    @Autowired
    private HandoverEvidenceService handoverEvidenceService;

    @Autowired
    private SaleResolutionService saleResolutionService;

    @Autowired
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Autowired
    private HandoverEvidenceRepository handoverEvidenceRepository;

    @Autowired
    private SaleResolutionRepository saleResolutionRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate txTemplate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return txTemplate;
    }

    private String shortVin(String prefix) {
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    private Long createApprovedVehicle(TransactionTemplate txTemplate) {
        return txTemplate.execute(status -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, shortVin("X1"), VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            return vehicleRepository.save(vehicle).getId();
        });
    }

    private Long createWaitingForPaymentAuction(TransactionTemplate txTemplate, Long vehicleId,
                                                 Long winnerId, BigDecimal winningBid) {
        return txTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            return auctionRepository.save(auction).getId();
        });
    }

    // ==================== end-to-end happy path via the real services ====================

    @Test
    @DisplayName("Полный путь: PaymentEvidence + HandoverEvidence -> SaleResolution SUCCESS -> DONE, всё персистентно")
    void fullHappyPath_PersistsCorrectlyAndTransitionsToDone() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);

        PaymentEvidence payment = txTemplate.execute(status ->
                paymentEvidenceService.recordPayment(auctionId, 100L, winningBid, 1L));
        HandoverEvidence handover = txTemplate.execute(status ->
                handoverEvidenceService.recordHandover(auctionId, vehicleId, 100L, 1L));
        SaleResolution resolution = txTemplate.execute(status ->
                saleResolutionService.createSuccess(auctionId, 1L));

        assertThat(payment.getId()).isNotNull();
        assertThat(handover.getId()).isNotNull();
        assertThat(resolution.getId()).isNotNull();
        assertThat(resolution.getPaymentEvidenceId()).isEqualTo(payment.getId());
        assertThat(resolution.getHandoverEvidenceId()).isEqualTo(handover.getId());

        // Re-fetch from a fresh transaction/read to prove real persistence, not in-memory state.
        PaymentEvidence persistedPayment = txTemplate.execute(status ->
                paymentEvidenceRepository.findByAuctionId(auctionId).orElseThrow());
        HandoverEvidence persistedHandover = txTemplate.execute(status ->
                handoverEvidenceRepository.findByAuctionId(auctionId).orElseThrow());
        SaleResolution persistedResolution = txTemplate.execute(status ->
                saleResolutionRepository.findByAuctionId(auctionId).orElseThrow());

        assertThat(persistedPayment.getAmount()).isEqualByComparingTo(winningBid);
        assertThat(persistedHandover.getVehicleId()).isEqualTo(vehicleId);
        assertThat(persistedResolution.getFinancialResolutionStatus())
                .isEqualTo(com.example.autoauction.sale.domain.FinancialResolutionStatus.PENDING);

        txTemplate.execute(status -> {
            saleResolutionService.markFinancialResolutionDone(persistedResolution.getId());
            return null;
        });

        SaleResolution afterDone = txTemplate.execute(status ->
                saleResolutionRepository.findById(persistedResolution.getId()).orElseThrow());
        assertThat(afterDone.getFinancialResolutionStatus())
                .isEqualTo(com.example.autoauction.sale.domain.FinancialResolutionStatus.DONE);
    }

    // ==================== FK integrity ====================
    //
    // NOT tested here, deliberately: payment_evidence.auction_id, handover_evidence.auction_id/
    // vehicle_id and sale_resolution's three FKs are all plain Long fields (no @ManyToOne/
    // @JoinColumn - see each entity's own Javadoc), matching the established, already-documented
    // convention in JpaSellerDiagnosticCommitmentEntity. Hibernate's ddl-auto=create-drop (this
    // test profile - Flyway is disabled, see application-test.properties) only auto-creates FK
    // constraints it knows about from a relationship mapping, which none of these entities
    // declare - so real FK enforcement is provable only against the actual Flyway-migrated
    // (Postgres) schema, exactly like that existing precedent's own disclosed limitation, not a
    // gap introduced by this slice. Confirmed empirically: an equivalent test attempting to save
    // evidence against a nonexistent auction_id/vehicle_id does NOT throw under this test
    // profile, for this exact reason.

    // ==================== UNIQUE integrity ====================

    @Test
    @DisplayName("UNIQUE: второй PaymentEvidence для того же auction_id отклоняется")
    void paymentEvidence_DuplicateAuctionId_ViolatesUniqueConstraint() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, new BigDecimal("1000.00"));

        txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, new BigDecimal("1000.00"), 1L, OffsetDateTime.now(), OffsetDateTime.now())));

        assertThatThrownBy(() -> txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, new BigDecimal("1000.00"), 2L, OffsetDateTime.now(), OffsetDateTime.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("UNIQUE: второй SaleResolution для того же auction_id отклоняется")
    void saleResolution_DuplicateAuctionId_ViolatesUniqueConstraint() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        Long paymentId = txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, winningBid, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());
        Long handoverId = txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());

        txTemplate.execute(status -> saleResolutionRepository.save(
                new SaleResolution(auctionId, 1L, OffsetDateTime.now(), paymentId, handoverId, OffsetDateTime.now())));

        assertThatThrownBy(() -> txTemplate.execute(status -> saleResolutionRepository.save(
                new SaleResolution(auctionId, 1L, OffsetDateTime.now(), paymentId, handoverId, OffsetDateTime.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================== CHECK constraints (native SQL - bypasses domain/enum guards
    // deliberately, to prove the DB's OWN independent enforcement, not merely the
    // application layer's) ====================

    @Test
    @DisplayName("CHECK: amount <= 0 отклоняется на уровне БД (native insert, минуя домен)")
    void paymentEvidence_NonPositiveAmount_ViolatesCheckConstraintAtDbLevel() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO payment_evidence (auction_id, buyer_id, amount, received_by_admin_id, received_at, created_at) " +
                            "VALUES (?, 100, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    auctionId);
            return null;
        })).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("CHECK: result вне ('SUCCESS','B','BUYER_FAULT','PLATFORM_FAULT','C') отклоняется на уровне БД (native insert, минуя Java enum)")
    void saleResolution_InvalidResult_ViolatesCheckConstraintAtDbLevel() {
        // X3-A: the CHECK constraint was widened (V35) to admit B/BUYER_FAULT/PLATFORM_FAULT/C
        // alongside SUCCESS - BUYER_FAULT specifically is no longer an invalid value, so this
        // test now uses a genuinely-still-invalid placeholder to keep proving the same DB-level
        // guarantee (an arbitrary garbage result value is rejected) without asserting the
        // opposite of the now-correct, intentionally-widened invariant.
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO sale_resolution (auction_id, result, resolved_by, resolved_at, financial_resolution_status, created_at) " +
                            "VALUES (?, 'INVALID_RESULT', 1, CURRENT_TIMESTAMP, 'PENDING', CURRENT_TIMESTAMP)",
                    auctionId);
            return null;
        })).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("CHECK: financial_resolution_status вне (PENDING/DONE/FAILED) отклоняется на уровне БД")
    void saleResolution_InvalidFinancialStatus_ViolatesCheckConstraintAtDbLevel() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, new BigDecimal("1000.00"));
        Long paymentId = txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, new BigDecimal("1000.00"), 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());
        Long handoverId = txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO sale_resolution (auction_id, result, resolved_by, resolved_at, payment_evidence_id, handover_evidence_id, financial_resolution_status, created_at) " +
                            "VALUES (?, 'SUCCESS', 1, CURRENT_TIMESTAMP, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)",
                    auctionId, paymentId, handoverId);
            return null;
        })).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("CHECK: SUCCESS без paymentEvidenceId отклоняется на уровне БД (native insert)")
    void saleResolution_SuccessWithoutPaymentEvidence_ViolatesCheckConstraintAtDbLevel() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, new BigDecimal("1000.00"));
        Long handoverId = txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO sale_resolution (auction_id, result, resolved_by, resolved_at, handover_evidence_id, financial_resolution_status, created_at) " +
                            "VALUES (?, 'SUCCESS', 1, CURRENT_TIMESTAMP, ?, 'PENDING', CURRENT_TIMESTAMP)",
                    auctionId, handoverId);
            return null;
        })).isInstanceOf(RuntimeException.class);
    }

    // ==================== claim-first financial status transitions ====================

    @Test
    @DisplayName("Claim-first: PENDING -> DONE успешен один раз, повторный claim в DONE/FAILED терпит неудачу")
    void financialResolutionStatus_ClaimFirstTransitions_BehaveCorrectly() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        Long paymentId = txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, winningBid, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());
        Long handoverId = txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());
        Long resolutionId = txTemplate.execute(status -> saleResolutionRepository.save(
                new SaleResolution(auctionId, 1L, OffsetDateTime.now(), paymentId, handoverId, OffsetDateTime.now())).getId());

        boolean firstClaim = txTemplate.execute(status -> saleResolutionRepository.claimFinancialResolutionDone(resolutionId));
        assertThat(firstClaim).isTrue();

        boolean secondClaimDone = txTemplate.execute(status -> saleResolutionRepository.claimFinancialResolutionDone(resolutionId));
        assertThat(secondClaimDone).as("DONE is terminal - a second DONE claim must not succeed").isFalse();

        boolean claimFailedAfterDone = txTemplate.execute(status -> saleResolutionRepository.claimFinancialResolutionFailed(resolutionId));
        assertThat(claimFailedAfterDone).as("DONE -> FAILED must be impossible").isFalse();

        boolean resetAfterDone = txTemplate.execute(status -> saleResolutionRepository.resetFinancialResolutionToPending(resolutionId));
        assertThat(resetAfterDone).as("DONE -> PENDING must be impossible (no silent rollback)").isFalse();

        SaleResolution finalState = txTemplate.execute(status -> saleResolutionRepository.findById(resolutionId).orElseThrow());
        assertThat(finalState.getFinancialResolutionStatus())
                .isEqualTo(com.example.autoauction.sale.domain.FinancialResolutionStatus.DONE);
    }

    @Test
    @DisplayName("Claim-first: PENDING -> FAILED -> PENDING (explicit reset) -> DONE, полный путь восстановления")
    void financialResolutionStatus_FailedThenResetThenDone_FullRecoveryPath() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        Long paymentId = txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, winningBid, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());
        Long handoverId = txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, OffsetDateTime.now(), OffsetDateTime.now())).getId());
        Long resolutionId = txTemplate.execute(status -> saleResolutionRepository.save(
                new SaleResolution(auctionId, 1L, OffsetDateTime.now(), paymentId, handoverId, OffsetDateTime.now())).getId());

        assertThat((boolean) txTemplate.execute(status -> saleResolutionRepository.claimFinancialResolutionFailed(resolutionId))).isTrue();
        assertThat((boolean) txTemplate.execute(status -> saleResolutionRepository.resetFinancialResolutionToPending(resolutionId))).isTrue();
        assertThat((boolean) txTemplate.execute(status -> saleResolutionRepository.claimFinancialResolutionDone(resolutionId))).isTrue();

        SaleResolution finalState = txTemplate.execute(status -> saleResolutionRepository.findById(resolutionId).orElseThrow());
        assertThat(finalState.getFinancialResolutionStatus())
                .isEqualTo(com.example.autoauction.sale.domain.FinancialResolutionStatus.DONE);
    }

    // ==================== concurrent/duplicate creation, deterministic DB uniqueness ====================

    @Test
    @DisplayName("Конкурентное создание: две попытки HandoverEvidence для одного auction_id - выигрывает первая, вторая конфликтует детерминированно")
    void concurrentHandoverEvidenceCreation_SecondAttemptDeterministicallyConflicts() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createApprovedVehicle(txTemplate);
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, new BigDecimal("1000.00"));

        // First attempt commits.
        txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, OffsetDateTime.now(), OffsetDateTime.now())));

        // Second attempt, simulating a concurrent admin double-submission - relies entirely on
        // the DB's own UNIQUE constraint, not on any application-level lock (none exists/none
        // needed for evidence tables, which are never re-read-then-mutated).
        assertThatThrownBy(() -> txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 2L, OffsetDateTime.now(), OffsetDateTime.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);

        Optional<HandoverEvidence> persisted = txTemplate.execute(status -> handoverEvidenceRepository.findByAuctionId(auctionId));
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getConfirmedByAdminId()).isEqualTo(1L);
    }
}
