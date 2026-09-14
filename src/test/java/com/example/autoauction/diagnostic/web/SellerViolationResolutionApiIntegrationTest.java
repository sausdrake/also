// diagnostic/web/SellerViolationResolutionApiIntegrationTest.java
package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity;
import com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
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
 * X3-E corrective (BLOCKER 4): full HTTP-layer, real-DB proof of the diagnostic-stage
 * seller-violation-resolution flow - reject(SELLER_VIOLATION) durably committed, then a
 * SEPARATE admin call to POST .../seller-violation-resolution, authenticated as ADMIN,
 * resolving the commitment to FINALIZED/C using SellerDiagnosticCommitment.policyVersionId
 * (never Auction.policyVersionId - no Auction exists anywhere in this flow).
 *
 * @SpringBootTest + @AutoConfigureMockMvc (not @WebMvcTest) - mirrors
 * VideoProcessingRetrySecurityTest's own proven-working convention in this environment (the
 * @WebMvcTest-based Admin*ControllerTest suite has a separate, pre-existing, unrelated
 * failure in this environment - see the X3-E pre-commit baseline comparison). Deliberately
 * NOT class-level @Transactional: the whole point of point 13 below is that the reject call
 * and the seller-violation-resolution call are genuinely separate, independently-committing
 * transactions - a test-owned rollback would hide that distinction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("X3-E corrective (BLOCKER 4): diagnostic-stage seller-violation-resolution API")
class SellerViolationResolutionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DiagnosticRequestService diagnosticRequestService;
    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired
    private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired
    private SellerViolationResolutionRepository sellerViolationResolutionRepository;
    @Autowired
    private DepositService depositService;
    @Autowired
    private DepositRepository depositRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PolicyVersionService policyVersionService;
    @Autowired
    private SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;

    private static String rejectEndpoint(Long requestId) {
        return "/api/v1/admin/diagnostic/reports/" + requestId + "/reject";
    }

    private static String resolutionEndpoint(Long requestId) {
        return "/api/v1/admin/diagnostic/reports/" + requestId + "/seller-violation-resolution";
    }

    private record Setup(Long requestId, Long commitmentId, Long sellerId) {}

    /** create -> freeze (under a policy with one configured violation) -> COMPLETED. Stops
     *  short of reject - each test rejects via the real HTTP endpoint itself. */
    private Setup createFrozenCompletedRequest(Long violationDefinitionId) {
        Long sellerId = 750_000L + (System.nanoTime() % 100_000L);
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User seller = new User(null, "x3eapi" + System.nanoTime(), "x3eapi" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long userId = userRepository.save(seller).getId();
        depositService.createDeposit(userId, new BigDecimal("50000"));

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(userId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("X3EAPI" + (System.nanoTime() % 100000000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();

        sellerDiagnosticCommitmentService.freezeCommitment(userId, requestId, new BigDecimal("10000"), new BigDecimal("4000"));

        DiagnosticRequest completed = diagnosticRequestRepository.findById(requestId).orElseThrow();
        completed.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
        diagnosticRequestRepository.save(completed);

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(requestId).orElseThrow();

        return new Setup(requestId, commitment.getId(), userId);
    }

    private Long publishPolicyWithOneViolation(String code, BigDecimal percentageValue) {
        Long violationDefinitionId = violationDefinitionJpaRepository.save(
                new JpaViolationDefinitionEntity(code, "test fixture", OffsetDateTime.now())).getId();
        Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
        policyVersionService.updateDraftViolation(policyVersionId, violationDefinitionId, PenaltyType.PERCENTAGE, percentageValue, 0L);
        Long revision = policyVersionService.getById(policyVersionId).getConfigurationRevision();
        policyVersionService.publish(policyVersionId, 2L, revision);
        return violationDefinitionId;
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    @DisplayName("Full flow: reject(SELLER_VIOLATION) durably committed -> separate ADMIN call resolves the " +
            "commitment to FINALIZED/C via SellerDiagnosticCommitment.policyVersionId, no Auction anywhere")
    void fullFlow_RejectThenResolve_FinalizesCommitmentWithCorrectPenalty() throws Exception {
        Long violationDefinitionId = publishPolicyWithOneViolation("X3E_API_TEST_VIOLATION", new BigDecimal("25"));
        Setup setup = createFrozenCompletedRequest(violationDefinitionId);

        // Step 1: reject with SELLER_VIOLATION via the real admin endpoint.
        mockMvc.perform(post(rejectEndpoint(setup.requestId()))
                        .param("reason", "seller refused handover")
                        .param("category", "SELLER_VIOLATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionCategory").value("SELLER_VIOLATION"));

        // Rejection is durably committed - re-read fresh state.
        DiagnosticRequest afterReject = diagnosticRequestRepository.findById(setup.requestId()).orElseThrow();
        assertThat(afterReject.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);
        SellerDiagnosticCommitment afterReject_commitment = sellerDiagnosticCommitmentRepository
                .findById(setup.commitmentId()).orElseThrow();
        assertThat(afterReject_commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        // Step 2: separate ADMIN call resolving the seller violation.
        String requestBody = "{\"violationDefinitionIds\": [" + violationDefinitionId + "]}";
        mockMvc.perform(post(resolutionEndpoint(setup.requestId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerDiagnosticCommitmentId").value(setup.commitmentId()))
                .andExpect(jsonPath("$.appliedPenaltyAmount").value(2500.00))
                .andExpect(jsonPath("$.refundAmount").value(7500.00));

        SellerDiagnosticCommitment finalCommitment = sellerDiagnosticCommitmentRepository
                .findById(setup.commitmentId()).orElseThrow();
        assertThat(finalCommitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(finalCommitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.C);
        assertThat(finalCommitment.getPenaltyAmount()).isEqualByComparingTo("2500.00");
        assertThat(finalCommitment.getRefundAmount()).isEqualByComparingTo("7500.00");

        assertThat(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(setup.commitmentId()))
                .isPresent();
        var resolution = sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(setup.commitmentId()).orElseThrow();
        assertThat(resolution.getTriggerSource())
                .isEqualTo(com.example.autoauction.diagnostic.commitment.domain.TriggerSource.DIAGNOSTIC_REJECTION);
        assertThat(resolution.getAuctionId()).as("no Auction is required or referenced for this trigger").isNull();
        assertThat(resolution.getPolicyVersionId())
                .as("must use SellerDiagnosticCommitment.policyVersionId, never any Auction.policyVersionId (none exists)")
                .isEqualTo(finalCommitment.getPolicyVersionId());

        var transactions = depositRepository.findTransactionsByUserId(setup.sellerId());
        assertThat(transactions).anyMatch(t ->
                t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.CHARGE
                        && t.getDescription() != null && t.getDescription().contains("штрафа за нарушение")
                        && t.getAuctionId() == null);
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    @DisplayName("Failed financial resolution (unknown violation id) does NOT roll back the already-committed " +
            "rejection - request stays REJECTED, commitment stays AWAITING_FINALIZATION, retryable")
    void failedResolution_DoesNotRollBackAlreadyCommittedRejection() throws Exception {
        Long violationDefinitionId = publishPolicyWithOneViolation("X3E_API_TEST_VIOLATION_2", new BigDecimal("25"));
        Setup setup = createFrozenCompletedRequest(violationDefinitionId);

        mockMvc.perform(post(rejectEndpoint(setup.requestId()))
                        .param("reason", "seller refused handover")
                        .param("category", "SELLER_VIOLATION"))
                .andExpect(status().isOk());

        // A failing resolution attempt (unknown violation id) - real failure through the real
        // endpoint, not mocked.
        mockMvc.perform(post(resolutionEndpoint(setup.requestId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"violationDefinitionIds\": [999999999]}"))
                .andExpect(status().isNotFound());

        // The rejection from the FIRST, already-committed call must remain intact.
        DiagnosticRequest afterFailedResolution = diagnosticRequestRepository.findById(setup.requestId()).orElseThrow();
        assertThat(afterFailedResolution.getStatus()).isEqualTo(DiagnosticRequest.DiagnosticRequestStatus.REJECTED);

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus())
                .as("commitment stays AWAITING_FINALIZATION - retryable via the same endpoint")
                .isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(setup.commitmentId())).isEmpty();

        // Retry with the correct id succeeds - proving the endpoint is genuinely retryable.
        mockMvc.perform(post(resolutionEndpoint(setup.requestId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"violationDefinitionIds\": [" + violationDefinitionId + "]}"))
                .andExpect(status().isOk());

        assertThat(sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow().getStatus())
                .isEqualTo(CommitmentStatus.FINALIZED);
    }

    @Test
    @DisplayName("Unauthenticated request to seller-violation-resolution -> 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(post(resolutionEndpoint(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"violationDefinitionIds\": [1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = {"USER"})
    @DisplayName("Role USER -> 403")
    void userRole_403() throws Exception {
        mockMvc.perform(post(resolutionEndpoint(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"violationDefinitionIds\": [1]}"))
                .andExpect(status().isForbidden());
    }
}
