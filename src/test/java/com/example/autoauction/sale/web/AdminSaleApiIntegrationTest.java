package com.example.autoauction.sale.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.SaleFinalizationMethod;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.settlement.domain.SettlementStatus;
import com.example.autoauction.settlement.domain.port.SettlementCorrectionRepository;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 1 (Sale Completion &amp; Failure API): full HTTP-layer, real-DB proof of the four
 * admin-only endpoints under {@code /api/v1/admin/sale/{auctionId}/...}. Mirrors
 * {@code SellerViolationResolutionApiIntegrationTest}'s proven convention in this environment
 * ({@code @SpringBootTest} + {@code @AutoConfigureMockMvc}, not {@code @WebMvcTest};
 * deliberately not class-level {@code @Transactional} so persisted terminal state and the
 * refund invariant can be observed from fresh reads).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Slice 1: Admin Sale API (payment / handover / complete / fail)")
class AdminSaleApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DepositService depositService;
    @Autowired private PaymentEvidenceRepository paymentEvidenceRepository;
    @Autowired private HandoverEvidenceRepository handoverEvidenceRepository;
    @Autowired private SaleResolutionRepository saleResolutionRepository;
    @Autowired private PaymentRefundEvidenceRepository paymentRefundEvidenceRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementCorrectionRepository settlementCorrectionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private PolicyVersionService policyVersionService;

    private final BigDecimal winningBid = new BigDecimal("1020000.00");

    private static String base(Long auctionId) {
        return "/api/v1/admin/sale/" + auctionId;
    }

    private record Fixture(Long auctionId, Long vehicleId, Long winnerId) {}

    /** Fresh IN_AUCTION vehicle + WAITING_FOR_PAYMENT auction + frozen winner deposit. */
    private Fixture waitingForPayment() {
        Long winnerId = 800_000L + (System.nanoTime() % 100_000_000L);
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                "S1" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
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

        depositService.createDeposit(winnerId, new BigDecimal("1000000"));
        depositService.freezeForAuction(winnerId, auctionId, new BigDecimal("50000"));
        depositService.markAsWinner(winnerId, auctionId);

        return new Fixture(auctionId, vehicleId, winnerId);
    }

    private record SettlementFixture(Long auctionId, Long vehicleId, Long winnerId, Long sellerId) {}

    /**
     * Step 2C: same as {@link #waitingForPayment()}, but with the full seller-identity chain
     * (User -&gt; DiagnosticRequest -&gt; SellerDiagnosticCommitment) plus a published PolicyVersion
     * and the Buy Now discriminator wired onto the Auction - the exact preconditions
     * {@code SettlementService.createPending} requires to avoid failing closed. Used only by the
     * tests that need {@code completeSale} to actually reach SOLD; {@link #waitingForPayment()}
     * remains untouched for every test that never reaches a successful completion.
     */
    private SettlementFixture waitingForPaymentWithSettlementPrerequisites(BigDecimal commissionRate) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User seller = new User(null, "settlesel" + System.nanoTime(), "settlesel" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long sellerId = userRepository.save(seller).getId();
        depositService.createDeposit(sellerId, new BigDecimal("50000"));

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(sellerId);
        request.setBrand("Toyota");
        request.setModel("Camry");
        request.setYear(2020);
        request.setVin("SETTLE" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long diagnosticRequestId = diagnosticRequestRepository.save(request).getId();

        // freezeCommitment() itself snapshots whatever PolicyVersion is currently published at
        // the moment it runs - the publish must happen first so that snapshot matches the same
        // policyVersionId this fixture later sets on the Auction.
        Long policyVersionId = policyVersionService.create(commissionRate, 1L).getId();
        policyVersionService.publish(policyVersionId);

        sellerDiagnosticCommitmentService.freezeCommitment(sellerId, diagnosticRequestId,
                new BigDecimal("10000"), new BigDecimal("4000"));
        // AuctionFinalizer.completeSale() also resolves Outcome A for this commitment (X3-D,
        // pre-existing, unrelated to Step 2C) - it requires AWAITING_FINALIZATION, same as
        // AuctionCommitmentResolver's own precondition for linking a commitment to a new auction.
        sellerDiagnosticCommitmentRepository.markAwaitingFinalization(diagnosticRequestId);
        Long commitmentId = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(diagnosticRequestId)
                .orElseThrow().getId();

        Long winnerId = 800_000L + (System.nanoTime() % 100_000_000L);
        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                "S2" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
        vehicle.submitForReview();
        vehicle.approve(1L, "admin");
        vehicle.markInAuction();
        Long vehicleId = vehicleRepository.save(vehicle).getId();

        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), new BigDecimal("1000000"), null,
                new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L /* adminId - never the seller */);
        auction.setPolicyVersionId(policyVersionId);
        auction.setSellerDiagnosticCommitmentId(commitmentId);
        auction.setSaleFinalizationMethod(SaleFinalizationMethod.ORDINARY_BID);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(winningBid);
        Long auctionId = auctionRepository.save(auction).getId();

        depositService.createDeposit(winnerId, new BigDecimal("1000000"));
        depositService.freezeForAuction(winnerId, auctionId, new BigDecimal("50000"));
        depositService.markAsWinner(winnerId, auctionId);

        return new SettlementFixture(auctionId, vehicleId, winnerId, sellerId);
    }

    private void recordPaymentAndHandover(Long auctionId) throws Exception {
        mockMvc.perform(post(base(auctionId) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(base(auctionId) + "/handover")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    // ==================== Authentication / authorization ====================

    @Test
    @DisplayName("Anonymous -> 401 на всех четырёх эндпоинтах")
    void anonymous_401() throws Exception {
        for (String suffix : new String[]{"/payment", "/handover", "/complete", "/fail"}) {
            mockMvc.perform(post(base(1L) + suffix)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"USER"})
    @DisplayName("Role USER -> 403")
    void userRole_403() throws Exception {
        for (String suffix : new String[]{"/payment", "/handover", "/complete", "/fail"}) {
            mockMvc.perform(post(base(1L) + suffix)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Role DIAGNOSTIC -> 403")
    void diagnosticRole_403() throws Exception {
        for (String suffix : new String[]{"/payment", "/handover", "/complete", "/fail"}) {
            mockMvc.perform(post(base(1L) + suffix)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ==================== Payment ====================

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Payment: валидная сумма -> 201, PaymentEvidence с buyerId = победитель")
    void payment_valid_201() throws Exception {
        Fixture f = waitingForPayment();

        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.auctionId").value(f.auctionId()))
                .andExpect(jsonPath("$.buyerId").value(f.winnerId()))
                .andExpect(jsonPath("$.amount").value(1020000.00));

        assertThat(paymentEvidenceRepository.findByAuctionId(f.auctionId())).isPresent();
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Payment: сумма != winningBid -> 400")
    void payment_wrongAmount_400() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 999999.00}"))
                .andExpect(status().isBadRequest());
        assertThat(paymentEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Payment: дубликат -> 409")
    void payment_duplicate_409() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Payment: несуществующий аукцион -> 404")
    void payment_auctionNotFound_404() throws Exception {
        mockMvc.perform(post(base(99_999_999L) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isNotFound());
    }

    // ==================== Handover ====================

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Handover: без PaymentEvidence -> 409")
    void handover_withoutPayment_409() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/handover")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        assertThat(handoverEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Handover: после оплаты -> 201; повтор -> 409")
    void handover_valid_thenDuplicate() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(base(f.auctionId()) + "/handover")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.auctionId").value(f.auctionId()))
                .andExpect(jsonPath("$.vehicleId").value(f.vehicleId()))
                .andExpect(jsonPath("$.buyerId").value(f.winnerId()));

        mockMvc.perform(post(base(f.auctionId()) + "/handover")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    // ==================== Complete ====================

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Complete: без payment/handover -> 409")
    void complete_withoutEvidence_409() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Complete: payment+handover -> 200 SOLD; Vehicle SOLD; НЕТ refund evidence; повтор -> 409")
    void complete_valid_thenRepeat() throws Exception {
        // Step 2C: this fixture must carry the full seller-identity/policy/discriminator chain -
        // completeSale() now also creates a PENDING Settlement in the same transaction, and that
        // creation fails closed (rolling back the whole SOLD transition) without it.
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());

        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.auctionId").value(f.auctionId()));

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SOLD);
        assertThat(vehicleRepository.findById(f.vehicleId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.SOLD);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId()))
                .as("no refund evidence on the successful SOLD path")
                .isFalse();

        // Step 2C: the Settlement side-effect of a successful completeSale().
        var settlement = settlementRepository.findByAuctionId(f.auctionId()).orElseThrow();
        assertThat(settlement.getSellerId())
                .as("seller must be the DiagnosticRequest.userId, never Auction.createdBy (10L)")
                .isEqualTo(f.sellerId());
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getExpectedBuyerPaidAmount()).isEqualByComparingTo(winningBid);
        assertThat(settlement.getExpectedSellerAmount())
                .isEqualByComparingTo(settlement.getExpectedBuyerPaidAmount()
                        .divide(new BigDecimal("1.02"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(settlement.getExpectedSellerAmount().add(settlement.getExpectedCommission()))
                .as("conservation: sellerAmount + commission == buyerPaidAmount")
                .isEqualByComparingTo(settlement.getExpectedBuyerPaidAmount());

        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    // ==================== Fail ====================

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail: outcome=SUCCESS -> 400")
    void fail_success_400() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail: неизвестный outcome -> 400")
    void fail_unknownOutcome_400() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"NONSENSE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail B (без оплаты): 200 SALE_FAILED, Vehicle APPROVED, faultParty=null, НЕТ refund evidence")
    void fail_B_noPayment() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"B\", \"reason\": \"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("B"))
                .andExpect(jsonPath("$.faultParty").doesNotExist());

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(vehicleRepository.findById(f.vehicleId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.APPROVED);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail BUYER_FAULT (после оплаты): 200, faultParty=BUYER, refund evidence = winningBid")
    void fail_buyerFault_afterPayment_createsRefund() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"BUYER_FAULT\", \"reason\": \"buyer no-show\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("BUYER_FAULT"))
                .andExpect(jsonPath("$.faultParty").value("BUYER"));

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SALE_FAILED);
        var refund = paymentRefundEvidenceRepository.findByAuctionId(f.auctionId());
        assertThat(refund).isPresent();
        assertThat(refund.orElseThrow().getRefundedAmount()).isEqualByComparingTo(winningBid);
        assertThat(refund.orElseThrow().getBuyerId()).isEqualTo(f.winnerId());
        assertThat(refund.orElseThrow().getRefundedByAdminId()).isEqualTo(1L);
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail PLATFORM_FAULT (после оплаты): 200, faultParty=PLATFORM, Vehicle NEEDS_REDIAGNOSTIC, refund = winningBid")
    void fail_platformFault_afterPayment_createsRefund() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"PLATFORM_FAULT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faultParty").value("PLATFORM"));

        assertThat(vehicleRepository.findById(f.vehicleId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.NEEDS_REDIAGNOSTIC);
        assertThat(paymentRefundEvidenceRepository.findByAuctionId(f.auctionId()).orElseThrow()
                .getRefundedAmount()).isEqualByComparingTo(winningBid);
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail C (историч. аукцион без commitment, пустой список violationIds, после оплаты): 200, faultParty=SELLER, refund создаётся")
    void fail_C_noCommitment_afterPayment_createsRefund() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\": \"C\", \"violationDefinitionIds\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("C"))
                .andExpect(jsonPath("$.faultParty").value("SELLER"));

        assertThat(paymentRefundEvidenceRepository.findByAuctionId(f.auctionId()).orElseThrow()
                .getRefundedAmount()).isEqualByComparingTo(winningBid);
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Fail: после SALE_FAILED повторный /fail и /complete -> 409")
    void fail_thenSecondTerminalOp_409() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"B\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"BUYER_FAULT\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Payment после SALE_FAILED -> 409 (аукцион уже терминальный)")
    void payment_afterSaleFailed_409() throws Exception {
        Fixture f = waitingForPayment();
        mockMvc.perform(post(base(f.auctionId()) + "/fail")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"B\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(base(f.auctionId()) + "/payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1020000.00}"))
                .andExpect(status().isConflict());
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId()).orElseThrow().getResult())
                .isEqualTo(SaleResolutionResult.B);
    }

    // ==================== Step 2C: Settlement fail-closed precondition ====================

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("Complete: историч. аукцион без sellerDiagnosticCommitmentId/discriminator -> 409, " +
            "SOLD/Settlement не создаются (весь successful-sale транзакшн откатывается)")
    void complete_legacyAuctionWithoutSettlementPrerequisites_rejectedAndRolledBack() throws Exception {
        // The plain waitingForPayment() fixture (no commitment/policy/discriminator) is exactly
        // the historical shape SettlementService.createPending must fail closed for.
        Fixture f = waitingForPayment();
        recordPaymentAndHandover(f.auctionId());

        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .as("the whole successful-sale transaction must roll back - Auction never reaches SOLD")
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(settlementRepository.existsByAuctionId(f.auctionId())).isFalse();
    }

    // ==================== Step 2C: Settlement payout recording ====================

    @Test
    @DisplayName("Settlement payout: anonymous -> 401")
    void settlementPayout_anonymous_401() throws Exception {
        mockMvc.perform(post(base(1L) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"USER"})
    @DisplayName("Settlement payout: role USER -> 403")
    void settlementPayout_userRole_403() throws Exception {
        mockMvc.perform(post(base(1L) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Settlement payout: role DIAGNOSTIC -> 403")
    void settlementPayout_diagnosticRole_403() throws Exception {
        mockMvc.perform(post(base(1L) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Settlement correction: anonymous -> 401")
    void settlementCorrection_anonymous_401() throws Exception {
        mockMvc.perform(post(base(1L) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"PAYOUT_INVALIDATED\", \"reason\": \"r\", \"idempotencyKey\": \"k\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"USER"})
    @DisplayName("Settlement correction: role USER -> 403")
    void settlementCorrection_userRole_403() throws Exception {
        mockMvc.perform(post(base(1L) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"PAYOUT_INVALIDATED\", \"reason\": \"r\", \"idempotencyKey\": \"k\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Settlement correction: role DIAGNOSTIC -> 403")
    void settlementCorrection_diagnosticRole_403() throws Exception {
        mockMvc.perform(post(base(1L) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"PAYOUT_INVALIDATED\", \"reason\": \"r\", \"idempotencyKey\": \"k\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement payout: валидная сумма -> 200, PAYOUT_RECORDED; точный повтор -> тот же admin, без 2-й записи")
    void settlementPayout_validThenExactReplay() throws Exception {
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYOUT_RECORDED"))
                .andExpect(jsonPath("$.attestedPayoutAmount").value(970000.00))
                .andExpect(jsonPath("$.recordedByAdminId").value(42));

        // Exact replay by a DIFFERENT admin - still a safe no-op; original admin preserved.
        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedByAdminId").value(42));

        assertThat(settlementRepository.findByAuctionId(f.auctionId()).orElseThrow().getRecordedByAdminId())
                .isEqualTo(42L);
    }

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement payout: конфликтующая сумма после PAYOUT_RECORDED -> 409, без перезаписи")
    void settlementPayout_conflictingAmount_rejected() throws Exception {
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.01}"))
                .andExpect(status().isConflict());

        assertThat(settlementRepository.findByAuctionId(f.auctionId()).orElseThrow().getAttestedPayoutAmount())
                .isEqualByComparingTo("970000.00");
    }

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement payout: отсутствующий Settlement -> 404")
    void settlementPayout_noSettlement_404() throws Exception {
        mockMvc.perform(post(base(99_999_999L) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 1}"))
                .andExpect(status().isNotFound());
    }

    // ==================== Step 2C: Settlement correction ====================

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement correction: AMOUNT_CORRECTION -> 201; повтор того же idempotencyKey -> тот же результат")
    void settlementCorrection_amountCorrection_thenExactReplay() throws Exception {
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.00}"))
                .andExpect(status().isOk());

        String body = "{\"correctionType\": \"AMOUNT_CORRECTION\", \"correctedAmount\": 960000.00, "
                + "\"reason\": \"recount of physical cash\", \"idempotencyKey\": \"corr-key-1\"}";

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctionType").value("AMOUNT_CORRECTION"))
                .andExpect(jsonPath("$.correctedAmount").value(960000.00));

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        Long settlementId = settlementRepository.findByAuctionId(f.auctionId()).orElseThrow().getId();
        assertThat(settlementCorrectionRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(settlementId))
                .as("the exact-key replay must never create a second correction row")
                .hasSize(1);
    }

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement correction: тот же idempotencyKey с другим reason -> 409, без второй записи")
    void settlementCorrection_sameKeyDifferentFingerprint_rejected() throws Exception {
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"AMOUNT_CORRECTION\", \"correctedAmount\": 960000.00, "
                                + "\"reason\": \"first reason\", \"idempotencyKey\": \"corr-key-2\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"AMOUNT_CORRECTION\", \"correctedAmount\": 960000.00, "
                                + "\"reason\": \"a completely different reason\", \"idempotencyKey\": \"corr-key-2\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement correction: PAYOUT_INVALIDATED -> 201, corrected* оба отсутствуют")
    void settlementCorrection_payoutInvalidated() throws Exception {
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(base(f.auctionId()) + "/settlement/payout")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attestedAmount\": 970000.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"PAYOUT_INVALIDATED\", "
                                + "\"reason\": \"original attestation was entirely erroneous\", "
                                + "\"idempotencyKey\": \"corr-key-3\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctionType").value("PAYOUT_INVALIDATED"))
                .andExpect(jsonPath("$.correctedAmount").doesNotExist())
                .andExpect(jsonPath("$.correctedSellerId").doesNotExist());

        // The original Settlement row remains entirely unchanged by the correction.
        var settlement = settlementRepository.findByAuctionId(f.auctionId()).orElseThrow();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
        assertThat(settlement.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
    }

    @Test
    @WithMockCustomUser(userId = 42L, roles = {"ADMIN"})
    @DisplayName("Settlement correction: пустой reason -> 400")
    void settlementCorrection_blankReason_400() throws Exception {
        SettlementFixture f = waitingForPaymentWithSettlementPrerequisites(new BigDecimal("0.02"));
        recordPaymentAndHandover(f.auctionId());
        mockMvc.perform(post(base(f.auctionId()) + "/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base(f.auctionId()) + "/settlement/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correctionType\": \"PAYOUT_INVALIDATED\", \"reason\": \"\", "
                                + "\"idempotencyKey\": \"corr-key-4\"}"))
                .andExpect(status().isBadRequest());
    }
}
