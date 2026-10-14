package com.example.autoauction.identity.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-004: full HTTP-layer proof of the KYC gate (real security filter chain, controllers,
 * GlobalExceptionHandler and H2 persistence). Every user-initiated protected operation -
 * bids, Buy Now, withdrawal, payment creation, diagnostic request (selling) - is refused with
 * 403 IDENTITY_VERIFICATION_REQUIRED and no side effect unless the caller's identity is
 * VERIFIED; User.trusted does not substitute for KYC; observing auctions stays open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MVP-004: KYC gate on protected operations - API behaviour")
class KycProtectedOperationsApiIntegrationTest {

    private static final String KYC_CODE = "IDENTITY_VERIFICATION_REQUIRED";
    private static final long NO_SUCH_AUCTION = 987_654_321L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;

    // ---------------------------------------------------------------- fixtures

    private Long newUser(boolean trusted, BigDecimal depositBalance) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String name = "kyc" + System.nanoTime();
        User user = trusted
                ? new User(null, name, name + "@test.com", "encoded", true, Set.of(role), true, 1L, OffsetDateTime.now())
                : new User(null, name, name + "@test.com", "encoded", true, Set.of(role));
        Long id = userRepository.save(user).getId();
        depositService.createDeposit(id, depositBalance);
        return id;
    }

    /** Persists the user's verification in exactly {@code status}, through the domain transitions. */
    private void identity(Long userId, IdentityVerificationStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        Long documentId = identityDocumentRepository.save(new IdentityDocument(
                "identity-documents/" + UUID.randomUUID(), userId, "image/jpeg", 1000L, now)).getId();
        IdentityVerification iv = IdentityVerification.firstSubmission(
                userId, "KG", DocumentType.NATIONAL_ID, "AN" + System.nanoTime(), documentId, now);
        switch (status) {
            case PENDING_REVIEW -> { }
            case VERIFIED -> iv.approve(now);
            case REJECTED -> iv.reject(now);
            case REVOKED -> { iv.approve(now); iv.revoke(now); }
        }
        identityVerificationRepository.save(iv);
    }

    private static MockHttpServletRequestBuilder as(Long userId, MockHttpServletRequestBuilder request) {
        TestUserPrincipal principal = TestUserPrincipal.createWithRoles(userId, "user" + userId, "USER");
        return request.with(authentication(
                new UsernamePasswordAuthenticationToken(principal, "password", principal.getAuthorities())));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder bid() {
        return json(post("/api/auctions/" + NO_SUCH_AUCTION + "/bids"), "{\"amount\":1000}");
    }

    private static MockHttpServletRequestBuilder buyNow() {
        return post("/api/auctions/" + NO_SUCH_AUCTION + "/buy-now");
    }

    private static MockHttpServletRequestBuilder withdraw(String idempotencyKey) {
        return json(post("/api/v1/deposits/withdraw"), "{\"amount\":100,\"idempotencyKey\":\"" + idempotencyKey + "\"}");
    }

    private static MockHttpServletRequestBuilder payment(String idempotencyKey) {
        return json(post("/api/v1/payments"), "{\"amount\":50000,\"idempotencyKey\":\"" + idempotencyKey + "\"}");
    }

    private static MockHttpServletRequestBuilder diagnosticRequest() {
        return json(post("/api/v1/diagnostic/requests"),
                "{\"brand\":\"Toyota\",\"model\":\"Camry\",\"year\":2020,\"vin\":\"JTNB11HK103456789\"}");
    }

    private void assertKycRefused(Long userId, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(as(userId, request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(KYC_CODE))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    private MvcResult performPastGate(Long userId, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(as(userId, request)).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .as("a VERIFIED user must never be refused by the KYC gate")
                .doesNotContain(KYC_CODE);
        return result;
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("never-verified user: every protected operation -> 403 IDENTITY_VERIFICATION_REQUIRED")
    void unverifiedUser_isRefusedEverywhere() throws Exception {
        Long userId = newUser(false, BigDecimal.ZERO);

        assertKycRefused(userId, bid());
        assertKycRefused(userId, buyNow());
        assertKycRefused(userId, withdraw(UUID.randomUUID().toString()));
        assertKycRefused(userId, payment(UUID.randomUUID().toString()));
        assertKycRefused(userId, diagnosticRequest());
    }

    @ParameterizedTest(name = "{0} identity is refused")
    @EnumSource(value = IdentityVerificationStatus.class, names = "VERIFIED", mode = EnumSource.Mode.EXCLUDE)
    void nonVerifiedStatuses_areRefused(IdentityVerificationStatus status) throws Exception {
        Long userId = newUser(true, BigDecimal.ZERO);
        identity(userId, status);

        assertKycRefused(userId, bid());
        assertKycRefused(userId, payment(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("User.trusted is not KYC: a trusted user without verified identity cannot bid or Buy Now")
    void trustedButUnverified_isRefused() throws Exception {
        Long userId = newUser(true, BigDecimal.ZERO);

        assertKycRefused(userId, bid());
        assertKycRefused(userId, buyNow());
    }

    @Test
    @DisplayName("refusal has no side effect: no withdrawal, no payment, no diagnostic request")
    void refusal_hasNoSideEffects() throws Exception {
        Long userId = newUser(false, new BigDecimal("1000"));
        String paymentKey = UUID.randomUUID().toString();

        assertKycRefused(userId, withdraw(UUID.randomUUID().toString()));
        assertKycRefused(userId, payment(paymentKey));
        assertKycRefused(userId, diagnosticRequest());

        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualByComparingTo("1000");
        assertThat(paymentRepository.findByCreationIdempotencyKey(paymentKey)).isEmpty();
        assertThat(diagnosticRequestRepository.findByUserId(userId, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    // ---------------------------------------------------------------- verified users pass

    @Test
    @DisplayName("VERIFIED user: withdrawal and payment creation complete")
    void verifiedUser_completesWithdrawalAndPayment() throws Exception {
        Long userId = newUser(false, new BigDecimal("1000"));
        identity(userId, IdentityVerificationStatus.VERIFIED);
        String paymentKey = UUID.randomUUID().toString();

        mockMvc.perform(as(userId, withdraw(UUID.randomUUID().toString()))).andExpect(status().isOk());
        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualByComparingTo("900");

        mockMvc.perform(as(userId, payment(paymentKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountRequested").value(50000.0));
        assertThat(paymentRepository.findByCreationIdempotencyKey(paymentKey)).isPresent();
    }

    @Test
    @DisplayName("VERIFIED + trusted user: bids, Buy Now and diagnostic requests reach their use cases")
    void verifiedUser_passesTheGateForBidsBuyNowAndDiagnosticRequests() throws Exception {
        Long userId = newUser(true, BigDecimal.ZERO);
        identity(userId, IdentityVerificationStatus.VERIFIED);

        // No such auction: the use case itself answers - never the KYC gate.
        assertThat(performPastGate(userId, bid()).getResponse().getStatus()).isBetween(400, 499);
        assertThat(performPastGate(userId, buyNow()).getResponse().getStatus()).isBetween(400, 499);
        performPastGate(userId, diagnosticRequest());
    }

    // ---------------------------------------------------------------- unchanged surfaces

    @Test
    @DisplayName("observing stays open: the public auction list needs neither login nor KYC")
    void publicAuctionList_staysOpen() throws Exception {
        mockMvc.perform(get("/api/v1/public/auctions")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the disabled self-service deposit keeps its stable 409 for unverified users")
    void disabledSelfServiceDeposit_keepsItsContract() throws Exception {
        Long userId = newUser(false, BigDecimal.ZERO);

        mockMvc.perform(as(userId, json(post("/api/v1/deposits/deposit"), "{\"amount\":100}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
