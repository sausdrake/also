package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 73 (G-N4): HTTP-layer proof for {@code GET /api/v1/admin/ops/status} - ADMIN-only access
 * (unauthenticated denied, plain USER denied), correct response shape (per-scheduler status list,
 * open reconciliation case count), and that the endpoint causes zero mutation. Mirrors {@code
 * AdminReconciliationControllerApiIntegrationTest}'s own conventions exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Operational status admin API - authorization boundary and response shape")
class AdminOperationalStatusControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private SchedulerHealthSignal schedulerHealthSignal;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-18T10:00:00Z");

    private static RequestPostProcessor user(Long userId, String... roles) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(userId, "u" + userId, roles));
    }

    private Long newOwningUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "opsuser" + System.nanoTime(), "opsuser" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Payment newPayment(Long userId) {
        Payment p = new Payment(userId, "merchant-ref-" + System.nanoTime(), new BigDecimal("500.00"),
                null, T0, T0.plusMinutes(30));
        return paymentRepository.save(p);
    }

    // ==================== Authorization ====================

    @Test
    @DisplayName("GET /status without authentication -> 401")
    void unauthenticated_Denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ops/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /status as a plain USER -> 403")
    void user_Denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ops/status").with(user(999101L, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /status as DIAGNOSTIC -> 403 (not an admin role)")
    void diagnostic_Denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ops/status").with(user(999102L, "DIAGNOSTIC")))
                .andExpect(status().isForbidden());
    }

    // ==================== Response shape ====================

    @Test
    @DisplayName("GET /status as ADMIN -> 200 with scheduler statuses and reconciliation case count")
    void admin_ReturnsStatus() throws Exception {
        schedulerHealthSignal.reportSuccess("TestScheduler.tick");
        schedulerHealthSignal.reportFailure("TestScheduler.otherTick");

        Long userId = newOwningUser();
        Payment p1 = newPayment(userId);
        Payment p2 = newPayment(userId);
        reconciliationCaseRepository.save(new ReconciliationCase(p1.getId(), ReconciliationCaseType.MISMATCH, T0));
        reconciliationCaseRepository.save(new ReconciliationCase(p2.getId(), ReconciliationCaseType.DUPLICATE, T0));

        mockMvc.perform(get("/api/v1/admin/ops/status").with(user(999103L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulers").isArray())
                .andExpect(jsonPath("$.schedulers[?(@.schedulerId=='TestScheduler.tick')].lastExecutionFailed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.schedulers[?(@.schedulerId=='TestScheduler.otherTick')].lastExecutionFailed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.openReconciliationCaseCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("GET /status as ADMIN never mutates a payment or reconciliation case")
    void admin_NoMutation() throws Exception {
        Long userId = newOwningUser();
        Payment p = newPayment(userId);
        ReconciliationCase c = reconciliationCaseRepository.save(
                new ReconciliationCase(p.getId(), ReconciliationCaseType.MISMATCH, T0));

        mockMvc.perform(get("/api/v1/admin/ops/status").with(user(999104L, "ADMIN")))
                .andExpect(status().isOk());

        ReconciliationCase reloaded = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus()).isEqualTo(c.getStatus());
    }
}
