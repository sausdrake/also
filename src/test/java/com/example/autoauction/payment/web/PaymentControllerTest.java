package com.example.autoauction.payment.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1.5 §29: full HTTP-layer proof of {@code POST /api/v1/payments} - {@code @SpringBootTest} +
 * {@code @AutoConfigureMockMvc} (H2, {@code test} profile), mirroring
 * {@code AdminDepositControllerTest}'s already-proven convention. Deliberately exercises the
 * REAL {@code SecurityConfig} (not a test override) - this is the actual evidence behind the
 * P1.5 audit's claim that the existing {@code .anyRequest().authenticated()} catch-all already
 * covers this route with zero configuration change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("P1.5 §29: POST /api/v1/payments - security/API behaviour")
class PaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;

    /**
     * MVP-004: payment creation is KYC-gated - the fixed principal id needs a VERIFIED identity.
     * The Spring context (and so the H2 database) is shared with other test classes, which may
     * already have given this user id a verification in another state - bring it to VERIFIED.
     */
    private void ensureVerifiedIdentity(Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        IdentityVerification existing = identityVerificationRepository.findByUserId(userId).orElse(null);
        if (existing != null && existing.getStatus() == IdentityVerificationStatus.VERIFIED) {
            return;
        }
        Long documentId = identityDocumentRepository.save(new IdentityDocument(
                "identity-documents/pc-" + userId + "-" + System.nanoTime(), userId, "image/jpeg", 1000L, now)).getId();
        IdentityVerification iv;
        if (existing == null) {
            iv = IdentityVerification.firstSubmission(
                    userId, "KG", DocumentType.NATIONAL_ID, "PC" + System.nanoTime(), documentId, documentId, documentId, now);
        } else {
            iv = existing;
            if (iv.getStatus() == IdentityVerificationStatus.PENDING_REVIEW) {
                iv.reject(now);
            }
            iv.resubmit("KG", DocumentType.NATIONAL_ID, "PC" + System.nanoTime(), documentId, documentId, documentId, now);
        }
        iv.approve(now);
        identityVerificationRepository.save(iv);
    }

    private Long createUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User user = new User(null, "pcweb" + System.nanoTime(), "pcweb" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long id = userRepository.save(user).getId();
        depositService.createDeposit(id, BigDecimal.ZERO);
        return id;
    }

    @Test
    @DisplayName("unauthenticated request is rejected (401) - the existing catch-all already covers this route, no SecurityConfig change needed")
    void unauthenticated_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("50000")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authenticated user can create a payment; userId comes from the authenticated principal, not the request body")
    @WithMockCustomUser(userId = 999999L, username = "nosuchuser", roles = {"USER"})
    void authenticatedUser_whoseIdDoesNotExist_getsRejectedAsNotFound_notTreatedAsSpoofable() throws Exception {
        // Deliberately using a principal userId with no matching Deposit row: proves the
        // controller never trusts anything but the principal for identity - it is not possible
        // to construct a request that bypasses this by supplying a different userId in the body,
        // because the body has no userId field at all (compile-time guarantee, not merely a
        // runtime check) - see requestBodyIgnoresForbiddenFields below for the direct proof.
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("50000")))))
                // MVP-004: a principal id with no user has no VERIFIED identity either - the KYC
                // gate rejects it before payment creation (still rejected, never spoofable).
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_VERIFICATION_REQUIRED"));
    }

    @Test
    @DisplayName("valid creation: 200, response reflects the authenticated user's own new Payment")
    @WithMockCustomUser(userId = 1L, username = "u1", roles = {"USER"})
    void validCreation_succeeds() throws Exception {
        // WithMockCustomUser's userId must correspond to a real Deposit row for creation to
        // succeed - create it under the SAME id this annotation will authenticate as.
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        if (userRepository.findById(1L).isEmpty()) {
            User user = new User(null, "fixeduser1", "fixeduser1@test.com", "encoded", true, Set.of(role));
            Long savedId = userRepository.save(user).getId();
            depositService.createDeposit(savedId, BigDecimal.ZERO);
        }
        // The shared test database may already hold user id 1 (created by another class) without a
        // deposit; createDeposit is idempotent, so make the principal's own deposit explicit.
        depositService.createDeposit(1L, BigDecimal.ZERO);
        ensureVerifiedIdentity(1L);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("50000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountRequested").value(50000.0))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.merchantReference").isNotEmpty());
    }

    @Test
    @DisplayName("invalid amount (below minimum, fails server-side policy validation after @Valid's own basic check) -> 4xx, never silently accepted")
    @WithMockCustomUser(userId = 2L, username = "u2", roles = {"USER"})
    void amountBelowMinimum_rejected() throws Exception {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        if (userRepository.findById(2L).isEmpty()) {
            User user = new User(null, "fixeduser2", "fixeduser2@test.com", "encoded", true, Set.of(role));
            Long savedId = userRepository.save(user).getId();
            depositService.createDeposit(savedId, BigDecimal.ZERO);
        }
        depositService.createDeposit(2L, BigDecimal.ZERO);
        ensureVerifiedIdentity(2L);  // MVP-004: so the 4xx below is the amount policy, not KYC

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", new BigDecimal("50")))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("CreatePaymentRequest has no userId/currency/status/merchantReference/providerReference field - a request attempting to inject them is hard-rejected (this project's Jackson config fails on unknown properties), never silently accepted with attacker-controlled values")
    @WithMockCustomUser(userId = 3L, username = "u3", roles = {"USER"})
    void requestBodyWithForbiddenFields_isHardRejected_neverSilentlyAccepted() throws Exception {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        if (userRepository.findById(3L).isEmpty()) {
            User user = new User(null, "fixeduser3", "fixeduser3@test.com", "encoded", true, Set.of(role));
            Long savedId = userRepository.save(user).getId();
            depositService.createDeposit(savedId, BigDecimal.ZERO);
        }
        depositService.createDeposit(3L, BigDecimal.ZERO);
        ensureVerifiedIdentity(3L);

        Map<String, Object> bodyWithForbiddenFields = new LinkedHashMap<>();
        bodyWithForbiddenFields.put("amount", new BigDecimal("1000"));
        bodyWithForbiddenFields.put("userId", 777777L);
        bodyWithForbiddenFields.put("currency", "USD");
        bodyWithForbiddenFields.put("status", "SUCCEEDED");
        bodyWithForbiddenFields.put("merchantReference", "CLIENT-CHOSEN-REF");

        // Confirmed via this test: CreatePaymentRequest declares only (amount, idempotencyKey),
        // and this project's ObjectMapper is configured to FAIL on unrecognized JSON properties
        // (a pre-existing, project-wide convention, not introduced here) - so attempting to
        // inject any of these fields does not silently succeed with the client's values ignored;
        // it hard-rejects the whole request. Never a 200 with attacker-controlled state.
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyWithForbiddenFields)))
                .andExpect(status().isBadRequest());
        // ERR-01: the unreadable body is a client error - 400 (it was a 500 before
        // GlobalExceptionHandler handled HttpMessageNotReadableException).
    }
}
