// auction/application/AuctionSaleCompletionIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.SaleFinalizationMethod;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * X2 (Authoritative Sale Completion): real-transaction proof of the atomic
 * WAITING_FOR_PAYMENT -> SOLD (Auction) + IN_AUCTION -> SOLD (Vehicle) transition performed by
 * AuctionFinalizer.completeSale(), covering the required caller shape (a pessimistic
 * findByIdWithLock() fetch, then completeSale() on the already-locked Auction, both inside one
 * physical transaction), every evidence-rejection path (reusing X1's own validation, not
 * duplicating it), the atomic-rollback proof when the Vehicle transition fails, repeat
 * completion, and genuine multi-threaded concurrent completion. Deliberately NOT class-level
 * {@code @Transactional} - matches the TransactionTemplate-based convention already established
 * in AuctionVehicleStartIntegrationTest/SaleEvidenceAndResolutionIntegrationTest, since several
 * tests need real, separately-committed transactions to observe persisted state and rollbacks
 * the way genuinely concurrent callers would.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("X2: атомарность Auction.SOLD + Vehicle.SOLD при завершении продажи (интеграционный тест)")
class AuctionSaleCompletionIntegrationTest {

    @Autowired
    private AuctionFinalizer auctionFinalizer;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private PaymentEvidenceService paymentEvidenceService;

    @Autowired
    private HandoverEvidenceService handoverEvidenceService;

    @Autowired
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Autowired
    private HandoverEvidenceRepository handoverEvidenceRepository;

    @Autowired
    private SaleResolutionRepository saleResolutionRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired
    private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired
    private PolicyVersionService policyVersionService;

    private final Long resolvedBy = 1L;

    private TransactionTemplate txTemplate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return txTemplate;
    }

    private String shortVin(String prefix) {
        // VIN column is VARCHAR(17) - keep well under that with a short unique numeric suffix.
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    private Long createInAuctionVehicle(TransactionTemplate txTemplate) {
        return txTemplate.execute(status -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, shortVin("X2"),
                    VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            vehicle.markInAuction();
            return vehicleRepository.save(vehicle).getId();
        });
    }

    private Long createVehicleWithStatus(TransactionTemplate txTemplate, VehicleStatus status) {
        return txTemplate.execute(txStatus -> {
            Vehicle vehicle = new Vehicle("Honda", "Civic", 2019, shortVin("X2"),
                    VehicleType.SEDAN, 1L, "owner");
            vehicle.setStatus(status);
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

    /**
     * Step 2C: same as {@link #createWaitingForPaymentAuction}, but wires the full seller-
     * identity/policy/discriminator chain {@code SettlementService.createPending} requires to
     * avoid failing closed - used only by the tests that need {@code completeSale} to actually
     * reach SOLD (T1/T10/T11); every other test in this class fails before ever reaching
     * Settlement creation and is left on the plain helper, unchanged.
     */
    private Long createSettlementCapableWaitingForPaymentAuction(TransactionTemplate txTemplate, Long vehicleId,
                                                                   Long winnerId, BigDecimal winningBid) {
        return txTemplate.execute(status -> {
            Role userRole = roleRepository.findByName("ROLE_USER")
                    .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
            User seller = new User(null, "x2settle" + System.nanoTime(), "x2settle" + System.nanoTime() + "@test.com",
                    "encoded", true, Set.of(userRole));
            Long sellerId = userRepository.save(seller).getId();
            depositService.createDeposit(sellerId, new BigDecimal("50000"));

            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(sellerId);
            request.setBrand("Toyota");
            request.setModel("Camry");
            request.setYear(2020);
            request.setVin(shortVin("X2S"));
            request.setDepositFrozen(new BigDecimal("10000"));
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            Long diagnosticRequestId = diagnosticRequestRepository.save(request).getId();

            // freezeCommitment() snapshots whatever PolicyVersion is currently published - the
            // publish must happen first so that snapshot matches the policyVersionId below.
            Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
            policyVersionService.publish(policyVersionId, 2L, 0L);

            sellerDiagnosticCommitmentService.freezeCommitment(sellerId, diagnosticRequestId,
                    new BigDecimal("10000"), new BigDecimal("4000"));
            // AuctionFinalizer.completeSale() also resolves Outcome A for this commitment (X3-D,
            // pre-existing, unrelated to Step 2C) - it requires AWAITING_FINALIZATION.
            sellerDiagnosticCommitmentRepository.markAwaitingFinalization(diagnosticRequestId);
            Long commitmentId = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(diagnosticRequestId)
                    .orElseThrow().getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), new BigDecimal("1000000"), null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setPolicyVersionId(policyVersionId);
            auction.setSellerDiagnosticCommitmentId(commitmentId);
            auction.setSaleFinalizationMethod(SaleFinalizationMethod.ORDINARY_BID);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            return auctionRepository.save(auction).getId();
        });
    }

    private Long createAuctionWithStatus(TransactionTemplate txTemplate, Long vehicleId, AuctionStatus status) {
        return txTemplate.execute(txStatus -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.plusHours(1), now.plusDays(7), 10L);
            auction.setStatus(status);
            return auctionRepository.save(auction).getId();
        });
    }

    private void recordValidEvidence(TransactionTemplate txTemplate, Long auctionId, Long vehicleId,
                                      Long winnerId, BigDecimal winningBid) {
        txTemplate.execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        txTemplate.execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));
    }

    /**
     * X3-D: completeSale() now calls depositService.releaseWinnerDeposit(winnerId, auctionId),
     * which requires the winner to actually have a frozen deposit for this auction - mirroring
     * the real production precondition (a bidder must have frozen collateral before ever
     * becoming a winner in the first place). This test file's winnerId (100L, a plain literal
     * with no FK - see the established actor-reference convention) is reused across every test
     * method in this class, so the underlying Deposit row is created at most once (a second
     * createDeposit() call for the same user_id would violate its UNIQUE constraint) - the
     * try/catch below is the deliberate, minimal way to make this idempotent across test
     * methods without restructuring every test's winnerId into a freshly-generated user, which
     * would be a far larger, unrelated change to this file's existing fixture shape.
     */
    private void ensureWinnerHasFrozenDeposit(Long winnerId, Long auctionId) {
        try {
            txTemplate().execute(status -> {
                depositService.createDeposit(winnerId, new BigDecimal("1000000"));
                return null;
            });
        } catch (Exception e) {
            // Deposit for this winnerId already exists (created by an earlier test method in
            // this class) - expected and fine, reuse it.
        }
        txTemplate().execute(status -> {
            depositService.freezeForAuction(winnerId, auctionId, new BigDecimal("50000"));
            return null;
        });
        txTemplate().execute(status -> {
            depositService.markAsWinner(winnerId, auctionId);
            return null;
        });
    }

    /** Mirrors the locked caller contract: obtain the pessimistic lock, then call completeSale()
     *  on the already-locked Auction, both inside the same physical transaction. */
    private void completeSaleViaLockedCaller(TransactionTemplate txTemplate, Long auctionId) {
        txTemplate.execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            auctionFinalizer.completeSale(locked, resolvedBy);
            return null;
        });
    }

    // ==================== T1: happy path ====================

    @Test
    @DisplayName("T1) Happy path: валидные evidence -> Auction SOLD + Vehicle SOLD + SaleResolution SUCCESS/PENDING, всё персистентно")
    void completeSale_ValidEvidence_PersistsSoldAuctionSoldVehicleAndSaleResolution() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createSettlementCapableWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        recordValidEvidence(txTemplate, auctionId, vehicleId, 100L, winningBid);
        ensureWinnerHasFrozenDeposit(100L, auctionId);

        completeSaleViaLockedCaller(txTemplate, auctionId);

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        var resolution = txTemplate.execute(status -> saleResolutionRepository.findByAuctionId(auctionId));

        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.SOLD);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.SOLD);
        assertThat(resolution).isPresent();
        assertThat(resolution.orElseThrow().getResult().name()).isEqualTo("SUCCESS");
        // X3-D: financialResolutionStatus now reaches DONE within this same transaction, once
        // the buyer-deposit consequence (and the commitment consequence, if any - this fixture
        // has no commitment) succeeds. Pre-X3-D this stayed PENDING forever; that was always
        // documented as "no future slice has moved it yet", not a permanent invariant.
        assertThat(resolution.orElseThrow().getFinancialResolutionStatus().name()).isEqualTo("DONE");

        // Step 2C: the same transaction also durably creates the Settlement side-effect.
        var settlement = txTemplate.execute(status -> settlementRepository.findByAuctionId(auctionId));
        assertThat(settlement).isPresent();
        assertThat(settlement.orElseThrow().getExpectedBuyerPaidAmount()).isEqualByComparingTo(winningBid);
    }

    // ==================== T2/T3: missing evidence ====================

    @Test
    @DisplayName("T2) Missing payment: отклоняется, Auction/Vehicle не меняются, SaleResolution не создаётся")
    void completeSale_MissingPayment_RejectsWithoutMutation() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        // Slice 1: HandoverEvidenceService now requires a pre-existing PaymentEvidence, so a
        // handover-without-payment state can no longer be produced through the service. Construct
        // the handover row directly via the repository port (same technique T4-T7 already use for
        // states unreachable through the public API) so completeSale()'s own missing-payment
        // rejection is still exercised.
        OffsetDateTime now2 = OffsetDateTime.now();
        txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 100L, 1L, now2, now2)));

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        assertNoMutationOccurred(txTemplate, auctionId, vehicleId, VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("T3) Missing handover: отклоняется, Auction/Vehicle не меняются, SaleResolution не создаётся")
    void completeSale_MissingHandover_RejectsWithoutMutation() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        txTemplate.execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        assertNoMutationOccurred(txTemplate, auctionId, vehicleId, VehicleStatus.IN_AUCTION);
    }

    // ==================== T4-T7: mismatched evidence ====================
    // Constructed directly via the repository ports, bypassing PaymentEvidenceService/
    // HandoverEvidenceService (which would themselves reject these exact mismatches at creation
    // time) - the same technique already established by X1's own integration test for states
    // unreachable through the public application-service API.

    @Test
    @DisplayName("T4) Invalid payment amount: отклоняется, Auction/Vehicle не меняются, SaleResolution не создаётся")
    void completeSale_InvalidPaymentAmount_RejectsWithoutMutation() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        // Slice 1: mismatched PaymentEvidence constructed directly via the repo (the service
        // would reject the amount). It is saved BEFORE the handover-service call because handover
        // now requires a payment row to exist first; the mismatch under test is completeSale()'s
        // own amount cross-check, not the handover precondition.
        OffsetDateTime now = OffsetDateTime.now();
        txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 100L, new BigDecimal("1.00"), 1L, now, now)));
        txTemplate.execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        assertNoMutationOccurred(txTemplate, auctionId, vehicleId, VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("T5) Invalid payment buyer: отклоняется, Auction/Vehicle не меняются, SaleResolution не создаётся")
    void completeSale_InvalidPaymentBuyer_RejectsWithoutMutation() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        // Wrong-buyer PaymentEvidence constructed directly (the service derives buyer from the
        // winner and could never produce this); saved before the handover-service call for the
        // same ordering reason as T4.
        OffsetDateTime now = OffsetDateTime.now();
        txTemplate.execute(status -> paymentEvidenceRepository.save(
                new PaymentEvidence(auctionId, 999L, winningBid, 1L, now, now)));
        txTemplate.execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        assertNoMutationOccurred(txTemplate, auctionId, vehicleId, VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("T6) Invalid handover vehicle: отклоняется, Auction/Vehicle не меняются, SaleResolution не создаётся")
    void completeSale_InvalidHandoverVehicle_RejectsWithoutMutation() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        Long otherVehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        txTemplate.execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        OffsetDateTime now = OffsetDateTime.now();
        txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, otherVehicleId, 100L, 1L, now, now)));

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        assertNoMutationOccurred(txTemplate, auctionId, vehicleId, VehicleStatus.IN_AUCTION);
    }

    @Test
    @DisplayName("T7) Invalid handover buyer: отклоняется, Auction/Vehicle не меняются, SaleResolution не создаётся")
    void completeSale_InvalidHandoverBuyer_RejectsWithoutMutation() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        txTemplate.execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        OffsetDateTime now = OffsetDateTime.now();
        txTemplate.execute(status -> handoverEvidenceRepository.save(
                new HandoverEvidence(auctionId, vehicleId, 999L, 1L, now, now)));

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        assertNoMutationOccurred(txTemplate, auctionId, vehicleId, VehicleStatus.IN_AUCTION);
    }

    // ==================== T8: wrong Auction status ====================

    @Test
    @DisplayName("T8) Auction не WAITING_FOR_PAYMENT: отклоняется ДО обращения к evidence, ничего не меняется")
    void completeSale_AuctionNotWaitingForPayment_RejectsBeforeTouchingEvidence() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        Long auctionId = createAuctionWithStatus(txTemplate, vehicleId, AuctionStatus.ACTIVE);

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);
        Boolean saleResolutionExists = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));
        assertThat(saleResolutionExists).isFalse();
    }

    // ==================== T9: atomic rollback when Vehicle transition fails ====================

    @Test
    @DisplayName("T9) Atomicity: Vehicle не IN_AUCTION -> markAsSold бросает -> ВСЯ транзакция откатывается (Auction остаётся WAITING_FOR_PAYMENT, Vehicle - исходный статус, SaleResolution не создаётся)")
    void completeSale_VehicleNotInAuction_WholeTransactionRollsBack() {
        TransactionTemplate txTemplate = txTemplate();
        // Exceptional state constructed directly, as instructed: a Vehicle that is APPROVED
        // (not IN_AUCTION) despite its auction already being WAITING_FOR_PAYMENT with valid
        // evidence - this cannot arise through the normal production call graph, but must still
        // be handled safely if it ever does.
        Long vehicleId = createVehicleWithStatus(txTemplate, VehicleStatus.APPROVED);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        recordValidEvidence(txTemplate, auctionId, vehicleId, 100L, winningBid);

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only IN_AUCTION can be sold");

        // Fresh reads from separate transactions - not in-memory objects - proving the SUCCESS
        // SaleResolution insert AND the Auction SOLD transition, both already flushed within the
        // failed transaction before the Vehicle mutation threw, were rolled back together.
        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        Boolean saleResolutionExists = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));
        assertThat(saleResolutionExists).isFalse();
    }

    // ==================== T10: repeat completion ====================

    @Test
    @DisplayName("T10) Repeat completion: первый вызов успешен, второй отклоняется; итог - Auction SOLD, Vehicle SOLD, ровно один SaleResolution")
    void completeSale_RepeatCompletion_SecondAttemptRejectedFinalStateUnchanged() {
        TransactionTemplate txTemplate = txTemplate();
        Long vehicleId = createInAuctionVehicle(txTemplate);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createSettlementCapableWaitingForPaymentAuction(txTemplate, vehicleId, 100L, winningBid);
        recordValidEvidence(txTemplate, auctionId, vehicleId, 100L, winningBid);
        ensureWinnerHasFrozenDeposit(100L, auctionId);

        completeSaleViaLockedCaller(txTemplate, auctionId);

        assertThatThrownBy(() -> completeSaleViaLockedCaller(txTemplate, auctionId))
                .isInstanceOf(IllegalStateException.class);

        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.SOLD);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.SOLD);
        Boolean saleResolutionExists = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));
        assertThat(saleResolutionExists).isTrue();
    }

    // ==================== T11: concurrent completion ====================

    @Test
    @DisplayName("T11) Concurrent completion: два одновременных вызова на один аукцион -> ровно один успешен, итог согласован, ровно один SaleResolution")
    void completeSale_ConcurrentCompletionAttempts_ExactlyOneSucceedsFinalStateConsistent() {
        TransactionTemplate setupTx = txTemplate();
        Long vehicleId = createInAuctionVehicle(setupTx);
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createSettlementCapableWaitingForPaymentAuction(setupTx, vehicleId, 100L, winningBid);
        recordValidEvidence(setupTx, auctionId, vehicleId, 100L, winningBid);
        ensureWinnerHasFrozenDeposit(100L, auctionId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Runnable attempt = () -> completeSaleViaLockedCaller(txTemplate(), auctionId);
        Runnable wrappedA = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                attempt.run();
            } catch (Throwable t) {
                errA.set(t);
            }
        };
        Runnable wrappedB = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                attempt.run();
            } catch (Throwable t) {
                errB.set(t);
            }
        };

        executor.submit(wrappedA);
        executor.submit(wrappedB);
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        // Exactly one of the two attempts may fail (the loser, blocked by the Auction's
        // pessimistic lock until the winner commits, then rejected by the WAITING_FOR_PAYMENT
        // guard once it re-reads the now-SOLD row) - both succeeding, or both failing, would
        // both violate the required invariant.
        boolean aFailed = errA.get() != null;
        boolean bFailed = errB.get() != null;
        assertThat(aFailed ^ bFailed)
                .as("Exactly one of the two concurrent completion attempts must fail, errA=%s, errB=%s",
                        errA.get(), errB.get())
                .isTrue();

        TransactionTemplate readTx = txTemplate();
        Auction persistedAuction = readTx.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = readTx.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.SOLD);
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.SOLD);
        Boolean saleResolutionExists = readTx.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));
        assertThat(saleResolutionExists).isTrue();
    }

    // ==================== shared assertion helper ====================

    private void assertNoMutationOccurred(TransactionTemplate txTemplate, Long auctionId, Long vehicleId,
                                           VehicleStatus expectedVehicleStatus) {
        Auction persistedAuction = txTemplate.execute(status -> auctionRepository.findById(auctionId).orElseThrow());
        Vehicle persistedVehicle = txTemplate.execute(status -> vehicleRepository.findById(vehicleId).orElseThrow());
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(persistedVehicle.getStatus()).isEqualTo(expectedVehicleStatus);
        Boolean saleResolutionExists = txTemplate.execute(status -> saleResolutionRepository.existsByAuctionId(auctionId));
        assertThat(saleResolutionExists).isFalse();
    }
}
