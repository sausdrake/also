package com.example.autoauction.payment.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 20 §15: HTTP-layer proof for {@code /api/v1/admin/reconciliation/**} - ADMIN-only access
 * (non-admin USER and DIAGNOSTIC both denied, unauthenticated denied), correct detail assembly,
 * RECONCILIATION-only evidence filtering in chronological order, the case-not-found vs.
 * broken-payment-reference distinction, and that a GET causes zero mutation. Mirrors {@code
 * AdminIdentityControllerApiIntegrationTest}'s own conventions exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Reconciliation admin API - authorization boundary and detail assembly")
class AdminReconciliationControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-18T10:00:00Z");

    private static RequestPostProcessor user(Long userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(userId, "u" + userId, roles));
    }

    private Long newOwningUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "recadmin" + System.nanoTime(), "recadmin" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Payment newPayment(Long userId) {
        Payment p = new Payment(userId, "merchant-ref-" + System.nanoTime(), new BigDecimal("500.00"),
                null, T0, T0.plusMinutes(30));
        return paymentRepository.save(p);
    }

    private ReconciliationCase newCase(Long paymentId, ReconciliationCaseType type) {
        return reconciliationCaseRepository.save(new ReconciliationCase(paymentId, type, T0));
    }

    private void appendEvent(Long paymentId, PaymentEventType type, PaymentEventSource source, String payload, OffsetDateTime at) {
        paymentEventRepository.append(new PaymentEvent(paymentId, type, source, payload, at));
    }

    // ==================== Authorization ====================

    @Test
    @DisplayName("GET /cases/{id} without authentication -> 401")
    void unauthenticated_Denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /cases/{id} as a plain USER -> 403")
    void user_Denied() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999001L, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /cases/{id} as DIAGNOSTIC -> 403 (not an admin role)")
    void diagnostic_Denied() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999002L, "DIAGNOSTIC")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /cases/{id} as ADMIN -> 200")
    void admin_Allowed() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999003L, "ADMIN")))
                .andExpect(status().isOk());
    }

    // ==================== Detail assembly ====================

    @Test
    @DisplayName("detail includes case + payment + chronologically-ordered RECONCILIATION-only evidence")
    void detail_AssemblesCorrectly_FiltersAndOrdersEvidence() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);

        // A non-RECONCILIATION event must NOT appear in the evidence list.
        appendEvent(p.getId(), PaymentEventType.CREATED, PaymentEventSource.CLIENT, null, T0.minusMinutes(5));
        // Two RECONCILIATION events, inserted out of chronological order, to prove the response
        // still reflects occurredAt ASC, id ASC - not insertion order.
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL,
                "requestedAmount=500.00;providerConfirmedAmount=480.00", T0.plusMinutes(10));
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL,
                "requestedAmount=500.00;providerConfirmedAmount=470.00", T0.plusMinutes(2));

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999004L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliationCase.id").value(c.getId()))
                .andExpect(jsonPath("$.reconciliationCase.paymentId").value(p.getId()))
                .andExpect(jsonPath("$.reconciliationCase.type").value("MISMATCH"))
                .andExpect(jsonPath("$.reconciliationCase.status").value("OPEN"))
                .andExpect(jsonPath("$.payment.id").value(p.getId()))
                .andExpect(jsonPath("$.payment.amountRequested").value(500.00))
                .andExpect(jsonPath("$.payment.currency").value("KGS"))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].rawPayload").value("requestedAmount=500.00;providerConfirmedAmount=470.00"))
                .andExpect(jsonPath("$.evidence[1].rawPayload").value("requestedAmount=500.00;providerConfirmedAmount=480.00"));
    }

    @Test
    @DisplayName("case with zero RECONCILIATION events -> empty evidence list, not an error")
    void detail_ZeroEvidence_ReturnsEmptyList() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.STUCK);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999005L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(0));
    }

    @Test
    @DisplayName("unknown case id -> 404")
    void detail_UnknownCase_404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", 999_999_999L).with(user(999006L, "ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("case referencing a non-existent payment id -> explicit 409, not a silently incomplete response")
    void detail_BrokenPaymentReference_ExplicitFailure() throws Exception {
        // A case whose paymentId does not resolve to any Payment row - a data-integrity break,
        // deliberately distinguished from the ordinary case-not-found 404 above.
        ReconciliationCase orphan = reconciliationCaseRepository.save(
                new ReconciliationCase(999_888_777L, ReconciliationCaseType.MISMATCH, T0));

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", orphan.getId()).with(user(999008L, "ADMIN")))
                .andExpect(status().isConflict());
    }

    // ==================== Read-only guarantee ====================

    @Test
    @DisplayName("GET causes zero mutation: case status/version and payment-event count unchanged")
    void detail_IsReadOnly_NoMutation() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = newCase(p.getId(), ReconciliationCaseType.MISMATCH);
        appendEvent(p.getId(), PaymentEventType.RECONCILIATION, PaymentEventSource.PROVIDER_POLL, "x", T0);

        ReconciliationCase before = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        int eventCountBefore = paymentEventRepository.findByPaymentId(p.getId()).size();

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/{id}", c.getId()).with(user(999007L, "ADMIN")))
                .andExpect(status().isOk());

        ReconciliationCase after = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        int eventCountAfter = paymentEventRepository.findByPaymentId(p.getId()).size();

        assertThat(after.getStatus()).isEqualTo(ReconciliationCaseStatus.OPEN);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getResolvedByAdminId()).isNull();
        assertThat(eventCountAfter).isEqualTo(eventCountBefore);
    }

}
