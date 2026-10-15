package com.example.autoauction.admin.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
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
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 2 · Step 2A: full HTTP-layer proof of the single canonical admin-adjust endpoint and of
 * the removal of the two duplicate routes. {@code @SpringBootTest} + {@code @AutoConfigureMockMvc}
 * (H2, `test` profile), mirroring {@code AdminSaleApiIntegrationTest}'s already-proven convention
 * in this environment - no Testcontainers/Docker needed for this slice's own API proof.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Slice 2 · Step 2A: canonical admin-adjust endpoint + duplicate-route removal")
class AdminDepositControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    // Slice 1A: deliberately left on the OLD 6-arg User constructor - this target user defaults
    // to untrusted (the correct default). canonicalEndpoint_AdminAdjust_Succeeds below therefore
    // already proves the required Slice 1A non-circularity invariant: adminAdjust must remain
    // available regardless of the target user's trust state, since admin-mediated deposit
    // funding and admin trust assignment are independent administrative operations.
    private Long createUserWithDeposit(BigDecimal balance) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User user = new User(null, "adjtarget" + System.nanoTime(), "adjtarget" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(userRole));
        Long id = userRepository.save(user).getId();
        depositService.createDeposit(id, balance);
        return id;
    }

    private String body(BigDecimal amount, String reason, String reference, String idempotencyKey) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("amount", amount, "reason", reason,
                        "reference", reference, "idempotencyKey", idempotencyKey));
    }

    @Test
    @DisplayName("Каноничный эндпоинт: ADMIN может применить корректировку; актор сохраняется из контекста аутентификации")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void canonicalEndpoint_AdminAdjust_Succeeds() throws Exception {
        Long targetUserId = createUserWithDeposit(new BigDecimal("10000"));

        mockMvc.perform(post("/api/v1/admin/deposits/" + targetUserId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new BigDecimal("1000"), "SUPPORT_CORRECTION", "TICKET-API-1",
                                "api-key-" + System.nanoTime())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(11000.0));
    }

    @Test
    @DisplayName("S11: reference и ключ идемпотентности длиннее колонки (100) отклоняются с 400, ничего не записывается")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void canonicalEndpoint_OverlongReferenceOrKey_Rejected() throws Exception {
        Long targetUserId = createUserWithDeposit(new BigDecimal("10000"));
        String longText = "R".repeat(101);

        mockMvc.perform(post("/api/v1/admin/deposits/" + targetUserId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new BigDecimal("1000"), "OTHER", longText, "api-key-long-" + System.nanoTime())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/deposits/" + targetUserId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new BigDecimal("1000"), "OTHER", "TICKET-LONG", longText)))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(depositService.getDepositByUserId(targetUserId).balance())
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Неаутентифицированный запрос к каноничному эндпоинту отклоняется (401)")
    void canonicalEndpoint_Unauthenticated_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deposits/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new BigDecimal("1000"), "OTHER", "TICKET-API-2", "api-key-2")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Запрос обычного пользователя (не ADMIN) к каноничному эндпоинту отклоняется (403)")
    @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
    void canonicalEndpoint_NonAdmin_Rejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deposits/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new BigDecimal("1000"), "OTHER", "TICKET-API-3", "api-key-3")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Актор всегда берётся из аутентифицированного контекста, а не из тела запроса " +
            "(AdminAdjustDepositRequest не содержит поля актора на уровне компиляции)")
    @WithMockCustomUser(userId = 77L, username = "admin2", roles = {"ADMIN"})
    void canonicalEndpoint_ActorAlwaysComesFromAuthenticatedContext() throws Exception {
        Long targetUserId = createUserWithDeposit(new BigDecimal("5000"));
        String idempotencyKey = "api-key-actor-" + System.nanoTime();

        mockMvc.perform(post("/api/v1/admin/deposits/" + targetUserId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new BigDecimal("500"), "OTHER", "TICKET-API-4", idempotencyKey)))
                .andExpect(status().isOk());

        DepositTransaction persisted = depositRepository.findTransactionByIdempotencyKey(idempotencyKey)
                .orElseThrow();
        // AdminAdjustDepositRequest (see its source) has exactly 4 fields - amount/reason/reference/
        // idempotencyKey - no actor field exists for a client to even attempt to supply one. The
        // persisted actor must be exactly the @WithMockCustomUser-authenticated id (77L), proving the
        // controller derives it solely from @CurrentUser, never from the request body.
        org.assertj.core.api.Assertions.assertThat(persisted.getPerformedByAdminId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("Бывший дублирующий маршрут AdminUserController больше не существует (404)")
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void legacyAdminUserControllerRoute_NoLongerExists() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/1/deposit/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"comment\":\"x\",\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Бывший дублирующий маршрут DepositController больше не существует (404)")
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void legacyDepositControllerRoute_NoLongerExists() throws Exception {
        mockMvc.perform(post("/api/v1/deposits/admin/users/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"comment\":\"x\",\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Легаси self-service /deposits/deposit по-прежнему возвращает стабильный структурированный 409")
    @WithMockCustomUser(userId = 1L, username = "user", roles = {"USER"})
    void legacySelfDeposit_StillReturnsStableDisabledResponse() throws Exception {
        mockMvc.perform(post("/api/v1/deposits/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Самостоятельное пополнение депозита недоступно. " +
                        "Зачисление средств выполняется администратором после проверки платежа."));
    }
}
