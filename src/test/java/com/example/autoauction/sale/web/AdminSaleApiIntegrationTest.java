package com.example.autoauction.sale.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
        Fixture f = waitingForPayment();
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
}
